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
import org.openrewrite.java.tree.*;

import java.util.List;
import java.util.stream.Collectors;

public class ReactorDoAfterSuccessOrErrorToTap extends Recipe {

    private static final MethodMatcher DO_AFTER_SUCCESS_OR_ERROR = new MethodMatcher("reactor.core.publisher.Mono doAfterSuccessOrError(..)");

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
                                      "            // this will be replaced\n" +
                                      "        }\n" +
                                      "\n" +
                                      "        @Override\n" +
                                      "        public void doOnNext(#{any()} " + doAfterSuccessOrErrorLambdaParams.get(0).getVariables().get(0).getSimpleName() + ") {\n" +
                                      "            // this will be replaced\n" +
                                      "        }\n" +
                                      "\n" +
                                      "        @Override\n" +
                                      "        public void doOnError(Throwable " + doAfterSuccessOrErrorLambdaParams.get(1).getVariables().get(0).getSimpleName() + ") {\n" +
                                      "            // this will be replaced\n" +
                                      "        }\n" +
                                      "    }\n" +
                                      ");";
                    J.MethodInvocation replacement = JavaTemplate
                            .builder(template)
                            .contextSensitive()
                            .imports("reactor.core.observability.DefaultSignalListener", "reactor.core.publisher.Mono", "reactor.core.publisher.SignalType")
                            .doAfterVariableSubstitution(System.out::println)
                            .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "reactor-core-3.5.+"))
                            .build()
                            .apply(getCursor(), mi.getCoordinates().replace(),
                                    mi.getSelect(),
                                    TypeTree.build(monoType.getClassName()).withType(monoType));

                    // These are the statements of the `doAfterSuccessOrError` BiConsumer lambda body
                    List<Statement> doAfterSuccesOrErrorStatements = ((J.Block)((J.Lambda) mi.getArguments().get(0)).getBody()).getStatements();
                    mi = replacement.withArguments(ListUtils.map(replacement.getArguments(), arg -> {
                        if (arg instanceof J.Lambda && ((J.Lambda) arg).getBody() instanceof J.NewClass) {
                            arg = ((J.Lambda) arg).withBody(((J.NewClass) ((J.Lambda) arg).getBody()).withBody(((J.NewClass) ((J.Lambda) arg).getBody()).getBody().withStatements(ListUtils.map(((J.NewClass) ((J.Lambda) arg).getBody()).getBody().getStatements(), stmt -> {
                                // here we have access to the method declarations inside the `DefaultSignalListener` inside the tap operator
                                // We could check the `doAfterSuccessOrError` statements and based on if they use a certain identifier place them in the correct method's body
                                return stmt;
                            }))));
                        }
                        return arg;
                    }));
                }
                return mi;
            }
        });
    }
}
