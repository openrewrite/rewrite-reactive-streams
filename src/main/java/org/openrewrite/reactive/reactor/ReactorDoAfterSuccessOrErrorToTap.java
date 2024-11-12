/*
 * Copyright 2024 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.reactive.reactor;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.FindMethods;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.openrewrite.java.tree.J.Binary.Type.Equal;
import static org.openrewrite.java.tree.J.Binary.Type.NotEqual;

public class ReactorDoAfterSuccessOrErrorToTap extends Recipe {

    private static final String DEFAULT_SIGNAL_LISTENER = "reactor.core.observability.DefaultSignalListener";
    private static final String SIGNAL_TYPE = "reactor.core.publisher.SignalType";

    private static final MethodMatcher DO_AFTER_SUCCESS_OR_ERROR = new MethodMatcher("reactor.core.publisher.Mono doAfterSuccessOrError(..)");
    private static final MethodMatcher DO_ON_ERROR = new MethodMatcher("* doOnError(..)");
    private static final MethodMatcher DO_ON_NEXT = new MethodMatcher("* doOnNext(..)");

    @Override
    public String getDisplayName() {
        return "Replace `doAfterSuccessOrError` calls with `tap` operator";
    }

    @Override
    public String getDescription() {
        return "As of reactor-core 3.5 the `doAfterSuccessOrError` method is removed, this recipe replaces it with the `tap` operator.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new FindMethods("reactor.core.publisher.Mono doAfterSuccessOrError(..)", false), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);
                if (DO_AFTER_SUCCESS_OR_ERROR.matches(mi)) {
                    JavaType.FullyQualified monoType = TypeUtils.asFullyQualified(((JavaType.Parameterized) mi.getMethodType().getReturnType()).getTypeParameters().get(0));
                    List<J.VariableDeclarations> doAfterSuccessOrErrorLambdaParams = ((J.Lambda) mi.getArguments().get(0)).getParameters().getParameters().stream().map(J.VariableDeclarations.class::cast).collect(Collectors.toList());
                    String template = "#{any()}.tap(() -> new DefaultSignalListener<>() {\n" +
                            "    @Override\n" +
                            "    public void doFinally(SignalType terminationType) {\n" +
                            "    }\n" +
                            "\n" +
                            "    @Override\n" +
                            "    public void doOnNext(" + monoType.getClassName() + " " + doAfterSuccessOrErrorLambdaParams.get(0).getVariables().get(0).getSimpleName() + ") {\n" +
                            "    }\n" +
                            "\n" +
                            "    @Override\n" +
                            "    public void doOnError(Throwable " + doAfterSuccessOrErrorLambdaParams.get(1).getVariables().get(0).getSimpleName() + ") {\n" +
                            "    }\n" +
                            "})";
                    J.MethodInvocation replacement = JavaTemplate
                            .builder(template)
                            .contextSensitive()
                            .imports(DEFAULT_SIGNAL_LISTENER, SIGNAL_TYPE)
                            .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "reactor-core-3.5.+", "reactive-streams-1.+"))
                            .build()
                            .apply(getCursor(), mi.getCoordinates().replace(), mi.getSelect());

                    maybeAddImport(DEFAULT_SIGNAL_LISTENER);
                    maybeAddImport(SIGNAL_TYPE);

                    List<Statement> doAfterSuccesOrErrorStatements = ((J.Block) ((J.Lambda) mi.getArguments().get(0)).getBody()).getStatements();
                    J.Identifier resultIdentifier = doAfterSuccessOrErrorLambdaParams.get(0).getVariables().get(0).getName();
                    J.Identifier errorIdentifier = doAfterSuccessOrErrorLambdaParams.get(1).getVariables().get(0).getName();
                    Map<J.Identifier, List<Statement>> statementsByIdentifier = extractStatements(doAfterSuccesOrErrorStatements, resultIdentifier, errorIdentifier);

                    mi = replacement.withArguments(ListUtils.map(replacement.getArguments(), arg -> {
                        if (arg instanceof J.Lambda && ((J.Lambda) arg).getBody() instanceof J.NewClass) {
                            J.NewClass dfltSgnlClass = (J.NewClass) ((J.Lambda) arg).getBody();
                            arg = ((J.Lambda) arg).withBody(dfltSgnlClass.withBody(dfltSgnlClass.getBody().withStatements(ListUtils.map(dfltSgnlClass.getBody().getStatements(), stmt -> {
                                if (stmt instanceof J.MethodDeclaration) {
                                    J.ClassDeclaration cd = getCursor().firstEnclosing(J.ClassDeclaration.class);
                                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                                    stmt = md.withBody(md.getBody().withStatements(
                                            DO_ON_NEXT.matches(md, cd) ? statementsByIdentifier.get(resultIdentifier) :
                                            DO_ON_ERROR.matches(md, cd) ? statementsByIdentifier.get(errorIdentifier) : statementsByIdentifier.get(null)
                                    ));
                                }
                                return stmt;
                            }))));
                        }
                        return arg;
                    }));
                }
                return maybeAutoFormat(method, mi, ctx);
            }

            private Map<J.Identifier, List<Statement>> extractStatements(List<Statement> doAfterSuccesOrErrorStatements, J.Identifier resultIdentifier, J.Identifier errorIdentifier) {
                List<Statement> resultStatements = new ArrayList<>();
                List<Statement> errorStatements = new ArrayList<>();
                List<Statement> unidentifiedStatements = new ArrayList<>();
                for (Statement olStmt : doAfterSuccesOrErrorStatements) {
                    if (olStmt instanceof J.If) {
                        J.If _if = (J.If) olStmt;
                        if (_if.getIfCondition().getTree() instanceof J.Binary) {
                            J.Binary ifCheck = (J.Binary) _if.getIfCondition().getTree();
                            if (isEqual(ifCheck.getLeft(), resultIdentifier) || isEqual(ifCheck.getRight(), resultIdentifier)) {
                                if (ifCheck.getOperator().equals(NotEqual)) {
                                    addIfStatements(_if, resultStatements, unidentifiedStatements, resultIdentifier);
                                    addElseStatements(_if, errorStatements);
                                }
                                if (ifCheck.getOperator().equals(Equal)) {
                                    addIfStatements(_if, errorStatements, unidentifiedStatements, errorIdentifier);
                                    addElseStatements(_if, resultStatements);
                                }
                            }
                            if (isEqual(ifCheck.getLeft(), errorIdentifier) || isEqual(ifCheck.getRight(), errorIdentifier)) {
                                if (ifCheck.getOperator().equals(NotEqual)) {
                                    addIfStatements(_if, errorStatements, unidentifiedStatements, errorIdentifier);
                                    addElseStatements(_if, resultStatements);
                                }
                                if (ifCheck.getOperator().equals(Equal)) {
                                    addIfStatements(_if, resultStatements, unidentifiedStatements, resultIdentifier);
                                    addElseStatements(_if, errorStatements);
                                }
                            }
                        }
                    } else {
                        if (usesIdentifier(olStmt, resultIdentifier)) {
                            resultStatements.add(olStmt);
                        } else if (usesIdentifier(olStmt, errorIdentifier)) {
                            errorStatements.add(olStmt);
                        } else {
                            unidentifiedStatements.add(olStmt);
                        }
                    }
                }
                return new HashMap<J.Identifier, List<Statement>>() {{
                    put(resultIdentifier, resultStatements);
                    put(errorIdentifier, errorStatements);
                    put(null, unidentifiedStatements);
                }};
            }

            private boolean isEqual(Expression expression, J.Identifier identifier) {
                return expression instanceof J.Identifier && isEqual((J.Identifier) expression, identifier);
            }

            private boolean isEqual(J.Identifier ident, J.Identifier identifier) {
                return ident.getFieldType().equals(identifier.getFieldType()) && TypeUtils.isOfType(identifier.getType(), ident.getType()) && identifier.getSimpleName().equals(ident.getSimpleName());
            }

            private boolean usesIdentifier(Statement olStmt, J.Identifier ident) {
                AtomicBoolean usesIdentifier = new AtomicBoolean(false);
                new JavaIsoVisitor<AtomicBoolean>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean usesIdentifier) {
                        if (isEqual(ident, identifier)) {
                            usesIdentifier.set(true);
                        }
                        return identifier;
                    }
                }.visit(olStmt, usesIdentifier);
                return usesIdentifier.get();
            }

            private void addIfStatements(J.If _if, List<Statement> statements, List<Statement> unidentifiedStatements, J.Identifier identifier) {
                for (Statement thenStatement : ((J.Block) _if.getThenPart()).getStatements()) {
                    if (usesIdentifier(thenStatement, identifier)) {
                        statements.add(thenStatement);
                    } else {
                        unidentifiedStatements.add(thenStatement);
                    }
                }
            }

            private void addElseStatements(J.If _if, List<Statement> statements) {
                J.If.Else _else = _if.getElsePart();
                if (_else != null) {
                    statements.addAll(((J.Block) _else.getBody()).getStatements());
                }
            }
        });
    }
}
