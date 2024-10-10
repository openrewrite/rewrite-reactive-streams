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

public class ReactorDoAfterSuccessOrErrorToTap extends Recipe {

    private static final String DEFAULT_SIGNAL_LISTENER = "reactor.core.observability.DefaultSignalListener";
    private static final String SIGNAL_TYPE = "reactor.core.publisher.SignalType";

    private static final MethodMatcher DO_AFTER_SUCCESS_OR_ERROR = new MethodMatcher("reactor.core.publisher.Mono doAfterSuccessOrError(..)");
    private static final MethodMatcher DEFAULT_SIGNAL_LISTENER_DO_FINALLY = new MethodMatcher("* doFinally(..)");
    private static final MethodMatcher DEFAULT_SIGNAL_LISTENER_DO_ON_ERROR = new MethodMatcher("* doOnError(..)");
    private static final MethodMatcher DEFAULT_SIGNAL_LISTENER_DO_ON_NEXT = new MethodMatcher("* doOnNext(..)");

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
                                      "        @Override\n" +
                                      "        public void doFinally(SignalType terminationType) {\n" +
                                      "        }\n" +
                                      "\n" +
                                      "        @Override\n" +
                                      "        public void doOnNext(" + monoType.getClassName() + " " + doAfterSuccessOrErrorLambdaParams.get(0).getVariables().get(0).getSimpleName() + ") {\n" +
                                      "        }\n" +
                                      "\n" +
                                      "        @Override\n" +
                                      "        public void doOnError(Throwable " + doAfterSuccessOrErrorLambdaParams.get(1).getVariables().get(0).getSimpleName() + ") {\n" +
                                      "        }\n" +
                                      "    }\n" +
                                      ");";
                    J.MethodInvocation replacement = JavaTemplate
                            .builder(template)
                            .contextSensitive()
                            .imports(DEFAULT_SIGNAL_LISTENER, SIGNAL_TYPE)
                            .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "reactor-core-3.5.+"))
                            .build()
                            .apply(getCursor(), mi.getCoordinates().replace(), mi.getSelect());
                    maybeAddImport(DEFAULT_SIGNAL_LISTENER);
                    maybeAddImport(SIGNAL_TYPE);

                    // These are the statements of the `doAfterSuccessOrError` BiConsumer lambda body
                    List<Statement> doAfterSuccesOrErrorStatements = ((J.Block) ((J.Lambda) mi.getArguments().get(0)).getBody()).getStatements();
                    J.Identifier resultIdentifier = doAfterSuccessOrErrorLambdaParams.get(0).getVariables().get(0).getName();
                    J.Identifier errorIdentifier = doAfterSuccessOrErrorLambdaParams.get(1).getVariables().get(0).getName();
                    Map<J.Identifier, List<Statement>> statementsByIdentifier = extractStatements(doAfterSuccessOrErrorLambdaParams, doAfterSuccesOrErrorStatements, resultIdentifier, errorIdentifier);

                    mi = replacement.withArguments(ListUtils.map(replacement.getArguments(), arg -> {
                        if (arg instanceof J.Lambda && ((J.Lambda) arg).getBody() instanceof J.NewClass) {
                            arg = ((J.Lambda) arg).withBody(((J.NewClass) ((J.Lambda) arg).getBody()).withBody(((J.NewClass) ((J.Lambda) arg).getBody()).getBody().withStatements(ListUtils.map(((J.NewClass) ((J.Lambda) arg).getBody()).getBody().getStatements(), stmt -> {
                                // here we have access to the method declarations inside the `DefaultSignalListener` inside the tap operator
                                // We could check the `doAfterSuccessOrError` statements and based on if they use a certain identifier place them in the correct method's body
                                if (stmt instanceof J.MethodDeclaration) {
                                    J.ClassDeclaration cd = getCursor().firstEnclosing(J.ClassDeclaration.class);
                                    if (DEFAULT_SIGNAL_LISTENER_DO_FINALLY.matches((J.MethodDeclaration) stmt, cd)) {
                                        stmt = ((J.MethodDeclaration) stmt).withBody(((J.MethodDeclaration) stmt).getBody().withStatements(statementsByIdentifier.get(null)));
                                    }
                                    if (DEFAULT_SIGNAL_LISTENER_DO_ON_NEXT.matches((J.MethodDeclaration) stmt, cd)) {
                                        stmt = ((J.MethodDeclaration) stmt).withBody(((J.MethodDeclaration) stmt).getBody().withStatements(statementsByIdentifier.get(resultIdentifier)));
                                    }
                                    if (DEFAULT_SIGNAL_LISTENER_DO_ON_ERROR.matches((J.MethodDeclaration) stmt, cd)) {
                                        stmt = ((J.MethodDeclaration) stmt).withBody(((J.MethodDeclaration) stmt).getBody().withStatements(statementsByIdentifier.get(errorIdentifier)));
                                    }
                                }
                                return stmt;
                            }))));
                        }
                        return arg;
                    }));
                }
                return mi;
            }

            private boolean usesIdentifier(Statement olStmt, J.Identifier ident) {
                AtomicBoolean usesIdentifier = new AtomicBoolean(false);
                new JavaIsoVisitor<AtomicBoolean>() {

                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, AtomicBoolean usesIdentifier) {
                        if (identifiersEqual(ident, identifier)) {
                            usesIdentifier.set(true);
                        }
                        return identifier;
                    }
                }.visit(olStmt, usesIdentifier);
                return usesIdentifier.get();
            }

            private boolean identifiersEqual(J.Identifier ident, J.Identifier identifier) {
                return ident.getFieldType().equals(identifier.getFieldType()) && TypeUtils.isOfType(identifier.getType(), ident.getType()) && identifier.getSimpleName().equals(ident.getSimpleName());
            }

            private Map<J.Identifier, List<Statement>> extractStatements(List<J.VariableDeclarations> doAfterSuccessOrErrorLambdaParams, List<Statement> doAfterSuccesOrErrorStatements, J.Identifier resultIdentifier, J.Identifier errorIdentifier) {
                List<Statement> resultStatements = new ArrayList<>();
                List<Statement> errorStatements = new ArrayList<>();
                List<Statement> unidentifiedStatements = new ArrayList<>();
                for (Statement olStmt : doAfterSuccesOrErrorStatements) {
                    if (olStmt instanceof J.If) {
                        if (((J.If) olStmt).getIfCondition().getTree() instanceof J.Binary) {
                            J.Binary ifCheck = (J.Binary) ((J.If) olStmt).getIfCondition().getTree();
                            if ((ifCheck.getLeft() instanceof J.Identifier && identifiersEqual((J.Identifier) ifCheck.getLeft(), resultIdentifier)) || (ifCheck.getRight() instanceof J.Identifier && identifiersEqual((J.Identifier) ifCheck.getRight(), resultIdentifier))) {
                                if (ifCheck.getOperator().equals(J.Binary.Type.NotEqual)) {
                                    for (Statement thenStatement : ((J.Block) ((J.If) olStmt).getThenPart()).getStatements()) {
                                        if (usesIdentifier(thenStatement, resultIdentifier)) {
                                            resultStatements.add(thenStatement);
                                        } else {
                                            unidentifiedStatements.add(thenStatement);
                                        }
                                    }
                                    errorStatements.addAll(((J.Block) ((J.If) olStmt).getElsePart().getBody()).getStatements());
                                }
                                if (ifCheck.getOperator().equals(J.Binary.Type.Equal)) {
                                    for (Statement thenStatement : ((J.Block) ((J.If) olStmt).getThenPart()).getStatements()) {
                                        if (usesIdentifier(thenStatement, errorIdentifier)) {
                                            errorStatements.add(thenStatement);
                                        } else {
                                            unidentifiedStatements.add(thenStatement);
                                        }
                                    }
                                    resultStatements.addAll(((J.Block) ((J.If) olStmt).getElsePart().getBody()).getStatements());
                                }
                            }
                            if ((ifCheck.getLeft() instanceof J.Identifier && identifiersEqual((J.Identifier) ifCheck.getLeft(), errorIdentifier)) || (ifCheck.getRight() instanceof J.Identifier && identifiersEqual((J.Identifier) ifCheck.getRight(), errorIdentifier))) {
                                if (ifCheck.getOperator().equals(J.Binary.Type.NotEqual)) {
                                    for (Statement thenStatement : ((J.Block) ((J.If) olStmt).getThenPart()).getStatements()) {
                                        if (usesIdentifier(thenStatement, errorIdentifier)) {
                                            errorStatements.add(thenStatement);
                                        } else {
                                            unidentifiedStatements.add(thenStatement);
                                        }
                                    }
                                    resultStatements.addAll(((J.Block) ((J.If) olStmt).getElsePart().getBody()).getStatements());
                                }
                                if (ifCheck.getOperator().equals(J.Binary.Type.Equal)) {
                                    for (Statement thenStatement : ((J.Block) ((J.If) olStmt).getThenPart()).getStatements()) {
                                        if (usesIdentifier(thenStatement, resultIdentifier)) {
                                            resultStatements.add(thenStatement);
                                        } else {
                                            unidentifiedStatements.add(thenStatement);
                                        }
                                    }
                                    errorStatements.addAll(((J.Block) ((J.If) olStmt).getElsePart().getBody()).getStatements());
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
        });
    }
}
