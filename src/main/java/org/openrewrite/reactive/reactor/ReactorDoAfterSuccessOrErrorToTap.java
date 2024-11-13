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

import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;
import java.util.stream.Collectors;

public class ReactorDoAfterSuccessOrErrorToTap extends Recipe {

    private static final String DEFAULT_SIGNAL_LISTENER = "reactor.core.observability.DefaultSignalListener";
    private static final String OPERATORS = "reactor.core.publisher.Operators";
    private static final String SIGNAL_TYPE = "reactor.core.publisher.SignalType";
    private static final String REACTOR_CONTEXT = "reactor.util.context.Context";

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
        return Preconditions.check(new UsesMethod<>(DO_AFTER_SUCCESS_OR_ERROR), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);
                if (DO_AFTER_SUCCESS_OR_ERROR.matches(mi) && mi.getArguments().get(0) instanceof J.Lambda) {
                    J.MethodInvocation replacement = JavaTemplate
                            .builder(template(mi))
                            .contextSensitive()
                            .imports(DEFAULT_SIGNAL_LISTENER, OPERATORS, SIGNAL_TYPE, REACTOR_CONTEXT)
                            .staticImports(SIGNAL_TYPE + ".CANCEL")
                            .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "reactor-core-3.5.+", "reactive-streams-1.+"))
                            .build()
                            .apply(getCursor(), mi.getCoordinates().replace(), mi.getSelect());

                    maybeAddImport(DEFAULT_SIGNAL_LISTENER);
                    maybeAddImport(OPERATORS);
                    maybeAddImport(SIGNAL_TYPE);
                    maybeAddImport(REACTOR_CONTEXT);
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
                                        List<Statement> newStatements = ListUtils.concatAll(md.getBody().getStatements(), originalStatements);
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
                String clazz = TypeUtils.asFullyQualified(((JavaType.Parameterized) mi.getMethodType().getReturnType()).getTypeParameters().get(0)).getClassName();
                String result = doAfterSuccessOrErrorLambdaParams.get(0).getVariables().get(0).getSimpleName();
                String error = doAfterSuccessOrErrorLambdaParams.get(1).getVariables().get(0).getSimpleName();
                //language=java
                return "#{any()}.tap(() -> new DefaultSignalListener<>() {" +
                       "    " + clazz + " " + result + ";" +
                       "    Throwable " + error + ";" +
                       "    boolean done;" +
                       "    boolean processedOnce;" +
                       "    Context currentContext;" +
                       "\n" +
                       "    @Override" +
                       "    public synchronized void doFinally(SignalType signalType) {" +
                       "      if (processedOnce) {" +
                       "          return;" +
                       "      }" +
                       "      processedOnce = true;" +
                       "      if (signalType == CANCEL) {" +
                       "          return;" +
                       "      }" +
                       "    }" +
                       "\n" +
                       "    @Override" +
                       "    public synchronized void doOnNext(" + clazz + " " + result + ") {" +
                       "        if (done) {" +
                       "            Operators.onDiscard(" + result + ", currentContext);" +
                       "            return;" +
                       "        }" +
                       "        this." + result + " = " + result + ";" +
                       "    }" +
                       "\n" +
                       "    @Override" +
                       "    public synchronized void doOnComplete() {" +
                       "        if (done) {" +
                       "            return;" +
                       "        }" +
                       "        this.done = true;" +
                       "    }" +
                       "\n" +
                       "    @Override" +
                       "    public synchronized void doOnError(Throwable " + error + ") {" +
                       "        if (done) {" +
                       "            Operators.onErrorDropped(" + error + ", currentContext);" +
                       "        }" +
                       "        this." + error + " = " + error + ";" +
                       "        this.done = true;" +
                       "    }" +
                       "\n" +
                       "    @Override" +
                       "    public Context addToContext(Context originalContext) {" +
                       "        currentContext = originalContext;" +
                       "        return originalContext;" +
                       "    }" +
                       "\n" +
                       "    @Override" +
                       "    public synchronized void doOnCancel() {" +
                       "        if (done) return;" +
                       "        this.done = true;" +
                       "        if (" + result + " != null) {" +
                       "            Operators.onDiscard(" + result + ", currentContext);" +
                       "        }" +
                       "    }" +
                       "})";
            }
        });
    }
}
