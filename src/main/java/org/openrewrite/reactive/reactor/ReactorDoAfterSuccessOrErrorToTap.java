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
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.*;

import java.util.List;

import static java.util.stream.Collectors.toList;

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
                if (DO_AFTER_SUCCESS_OR_ERROR.matches(mi)) {
                    boolean argumentLambda = mi.getArguments().get(0) instanceof J.Lambda;

                    Object[] templateArgs = {mi.getSelect()};
                    if (!argumentLambda) {
                        templateArgs = new Expression[]{mi.getSelect(), mi.getArguments().get(0)};
                    }

                    String signalListenerTemplate = newDefaultSignalListenerTemplate(mi);
                    J.MethodInvocation replacement = JavaTemplate
                            .builder("#{any()}.tap(() -> " + signalListenerTemplate + ")")
                            .contextSensitive()
                            .imports(
                                    DEFAULT_SIGNAL_LISTENER,
                                    OPERATORS,
                                    SIGNAL_TYPE,
                                    REACTOR_CONTEXT
                            )
                            .javaParser(JavaParser.fromJavaVersion().classpathFromResources(ctx, "reactor-core-3.5.+", "reactive-streams-1.+"))
                            .build()
                            .apply(updateCursor(mi), mi.getCoordinates().replace(), templateArgs);

                    maybeAddImport(DEFAULT_SIGNAL_LISTENER);
                    maybeAddImport(OPERATORS);
                    maybeAddImport(SIGNAL_TYPE);
                    maybeAddImport(REACTOR_CONTEXT);

                    if (argumentLambda) {
                        List<Statement> originalStatements = ((J.Block) ((J.Lambda) mi.getArguments().get(0)).getBody()).getStatements();
                        mi = replacement.withArguments(ListUtils.map(replacement.getArguments(), arg -> {
                            if (arg instanceof J.Lambda && ((J.Lambda) arg).getBody() instanceof J.NewClass) {
                                J.NewClass defaultSignalClass = (J.NewClass) ((J.Lambda) arg).getBody();
                                arg = ((J.Lambda) arg).withBody(defaultSignalClass.withBody(defaultSignalClass.getBody()
                                        .withStatements(ListUtils.map(defaultSignalClass.getBody().getStatements(), stmt -> {
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
                    } else {
                        mi = replacement;
                    }
                    return maybeAutoFormat(method, mi, ctx);
                }
                return mi;
            }

            private String newDefaultSignalListenerTemplate(J.MethodInvocation doAfterSuccessOrError) {
                String clazz = TypeUtils.asFullyQualified(((JavaType.Parameterized) doAfterSuccessOrError.getMethodType().getReturnType()).getTypeParameters().get(0)).getClassName();
                String result = "result";
                String error = "error";
                String impl = "#{any()}.accept(result, error);";

                Expression firstArgument = doAfterSuccessOrError.getArguments().get(0);
                if (firstArgument instanceof J.Lambda) {
                    List<J.VariableDeclarations> doAfterSuccessOrErrorLambdaParams = ((J.Lambda) firstArgument)
                            .getParameters().getParameters().stream()
                            .map(J.VariableDeclarations.class::cast)
                            .collect(toList());
                    result = doAfterSuccessOrErrorLambdaParams.get(0).getVariables().get(0).getSimpleName();
                    error = doAfterSuccessOrErrorLambdaParams.get(1).getVariables().get(0).getSimpleName();
                    impl = "";
                }

                //language=java
                return "new DefaultSignalListener<>() {" +
                       "    " + clazz + " " + result + ";" +
                       "    Throwable " + error + ";" +
                       "    boolean done;" +
                       "    boolean processedOnce;" +
                       "    Context currentContext;" +
                       "    @Override" +
                       "    public synchronized void doFinally(SignalType signalType) {" +
                       "      if (processedOnce) {" +
                       "          return;" +
                       "      }" +
                       "      processedOnce = true;" +
                       "      if (signalType == SignalType.CANCEL) {" +
                       "          return;" +
                       "      }" +
                       "      " + impl +
                       "    }" +
                       "    @Override" +
                       "    public synchronized void doOnNext(" + clazz + " " + result + ") {" +
                       "        if (done) {" +
                       "            Operators.onDiscard(" + result + ", currentContext);" +
                       "            return;" +
                       "        }" +
                       "        this." + result + " = " + result + ";" +
                       "    }" +
                       "    @Override" +
                       "    public synchronized void doOnComplete() {" +
                       "        this.done = true;" +
                       "    }" +
                       "    @Override" +
                       "    public synchronized void doOnError(Throwable " + error + ") {" +
                       "        if (done) {" +
                       "            Operators.onErrorDropped(" + error + ", currentContext);" +
                       "            return;" +
                       "        }" +
                       "        this." + error + " = " + error + ";" +
                       "        this.done = true;" +
                       "    }" +
                       "    @Override" +
                       "    public Context addToContext(Context originalContext) {" +
                       "        currentContext = originalContext;" +
                       "        return originalContext;" +
                       "    }" +
                       "    @Override" +
                       "    public synchronized void doOnCancel() {" +
                       "        if (done) {" +
                       "            return;" +
                       "        }" +
                       "        this.done = true;" +
                       "        if (" + result + " != null) {" +
                       "            Operators.onDiscard(" + result + ", currentContext);" +
                       "        }" +
                       "    }" +
                       "}";
            }
        });
    }
}
