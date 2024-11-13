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

import java.util.List;
import java.util.stream.Collectors;

public class ReactorDoAfterSuccessOrErrorToTap extends Recipe {

    private static final String DEFAULT_SIGNAL_LISTENER = "reactor.core.observability.DefaultSignalListener";
    private static final String SIGNAL_TYPE = "reactor.core.publisher.SignalType";

    private static final MethodMatcher DO_AFTER_SUCCESS_OR_ERROR = new MethodMatcher("reactor.core.publisher.Mono doAfterSuccessOrError(..)");
    private static final MethodMatcher DO_ON_FINALLY = new MethodMatcher("* doFinally(..)");

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
                    J.MethodInvocation replacement = JavaTemplate
                            .builder(template(mi))
                            .contextSensitive()
                            .imports(DEFAULT_SIGNAL_LISTENER, SIGNAL_TYPE)
                            .staticImports("reactor.core.publisher.SignalType.CANCEL")
                            .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "reactor-core-3.5.+", "reactive-streams-1.+"))
                            .build()
                            .apply(getCursor(), mi.getCoordinates().replace(), mi.getSelect());

                    maybeAddImport(DEFAULT_SIGNAL_LISTENER);
                    maybeAddImport(SIGNAL_TYPE);
                    maybeAddImport(SIGNAL_TYPE, "CANCEL");

                    List<Statement> originalStatements = ((J.Block) ((J.Lambda) mi.getArguments().get(0)).getBody()).getStatements();

                    mi = replacement.withArguments(ListUtils.map(replacement.getArguments(), arg -> {
                        if (arg instanceof J.Lambda && ((J.Lambda) arg).getBody() instanceof J.NewClass) {
                            J.NewClass dfltSgnlClass = (J.NewClass) ((J.Lambda) arg).getBody();
                            arg = ((J.Lambda) arg).withBody(dfltSgnlClass.withBody(dfltSgnlClass.getBody().withStatements(ListUtils.map(dfltSgnlClass.getBody().getStatements(), stmt -> {
                                if (stmt instanceof J.MethodDeclaration) {
                                    J.ClassDeclaration cd = getCursor().firstEnclosing(J.ClassDeclaration.class);
                                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                                    if (DO_ON_FINALLY.matches(md, cd)) {
                                        List<Statement> newStatements = ListUtils.concat(md.getBody().getStatements().get(0), originalStatements);
                                        stmt = md.withBody(md.getBody().withStatements(newStatements));
                                    }
                                }
                                return stmt;
                            }))));
                        }
                        return arg;
                    }));
                }
                return maybeAutoFormat(method, mi, ctx);
            }

            private String template(J.MethodInvocation mi) {
                List<J.VariableDeclarations> doAfterSuccessOrErrorLambdaParams = ((J.Lambda) mi.getArguments().get(0)).getParameters().getParameters().stream().map(J.VariableDeclarations.class::cast).collect(Collectors.toList());
                JavaType.FullyQualified monoType = TypeUtils.asFullyQualified(((JavaType.Parameterized) mi.getMethodType().getReturnType()).getTypeParameters().get(0));
                J.VariableDeclarations.NamedVariable result = doAfterSuccessOrErrorLambdaParams.get(0).getVariables().get(0);
                J.VariableDeclarations.NamedVariable error = doAfterSuccessOrErrorLambdaParams.get(1).getVariables().get(0);

                return "#{any()}.tap(() -> new DefaultSignalListener<>() {\n" +
                        "    " + monoType.getClassName() + " " + result.getSimpleName() + ";"+
                        "    Throwable " + error.getSimpleName() + ";"+
                        "\n" +
                        "    @Override\n" +
                        "    public void doFinally(SignalType signalType) {\n" +
                        "      if (signalType == CANCEL) {" +
                        "          return;" +
                        "      }" +
                        "    }\n" +
                        "\n" +
                        "    @Override\n" +
                        "    public void doOnNext(" + monoType.getClassName() + " " + result.getSimpleName() + ") {\n" +
                        "        this." + result.getSimpleName() + " = " + result.getSimpleName() + ";" +
                        "    }\n" +
                        "\n" +
                        "    @Override\n" +
                        "    public void doOnError(Throwable " + error.getSimpleName() + ") {\n" +
                        "        this." + error.getSimpleName() + " = " + error.getSimpleName() + ";" +
                        "    }\n" +
                        "})";
            }
        });
    }
}
