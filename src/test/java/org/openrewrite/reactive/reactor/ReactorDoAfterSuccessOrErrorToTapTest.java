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

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class ReactorDoAfterSuccessOrErrorToTapTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec
          .parser(JavaParser.fromJavaVersion()
            .classpathFromResources(new InMemoryExecutionContext(), "reactor-core-3.4", "reactive-streams"))
          .recipe(new ReactorDoAfterSuccessOrErrorToTap());
    }

    @Test
    @DocumentExample
    void refactorSuccessfully() {
        //language=java
        rewriteRun(
          java(
            """
              import reactor.core.publisher.Mono;

              class SomeClass {
                  void doSomething(Mono<String> mono) {
                      mono.doAfterSuccessOrError((result, error) -> {
                          if (error != null) {
                              System.out.println("error" + error);
                          } else {
                              System.out.println("success" + result);
                          }
                          System.out.println("other logs");
                      }).subscribe();
                  }
              }
              """,
            """
              import reactor.core.observability.DefaultSignalListener;
              import reactor.core.publisher.Mono;
              import reactor.core.publisher.Operators;
              import reactor.core.publisher.SignalType;
              import reactor.util.context.Context;

              class SomeClass {
                  void doSomething(Mono<String> mono) {
                      mono.tap(() -> new DefaultSignalListener<>() {
                          String result;
                          Throwable error;
                          boolean done;
                          boolean processedOnce;
                          Context currentContext;

                          @Override
                          public synchronized void doFinally(SignalType signalType) {
                              if (processedOnce) {
                                  return;
                              }
                              processedOnce = true;
                              if (signalType == SignalType.CANCEL) {
                                  return;
                              }
                              if (error != null) {
                                  System.out.println("error" + error);
                              } else {
                                  System.out.println("success" + result);
                              }
                              System.out.println("other logs");
                          }

                          @Override
                          public synchronized void doOnNext(String result) {
                              if (done) {
                                  Operators.onDiscard(result, currentContext);
                                  return;
                              }
                              this.result = result;
                          }

                          @Override
                          public synchronized void doOnComplete() {
                              if (done) {
                                  return;
                              }
                              this.done = true;
                          }

                          @Override
                          public synchronized void doOnError(Throwable error) {
                              if (done) {
                                  Operators.onErrorDropped(error, currentContext);
                              }
                              this.error = error;
                              this.done = true;
                          }

                          @Override
                          public Context addToContext(Context originalContext) {
                              currentContext = originalContext;
                              return originalContext;
                          }

                          @Override
                          public synchronized void doOnCancel() {
                              if (done) return;
                              this.done = true;
                              if (result != null) {
                                  Operators.onDiscard(result, currentContext);
                              }
                          }
                      }).subscribe();
                  }
              }
              """
          )
        );
    }

    @Test
    void refactorSuccessfullyWhenBiConsumerVariableIsUsed() {
        //language=java
        rewriteRun(
          java(
            """
              import java.util.function.BiConsumer;
              import reactor.core.publisher.Mono;

              class SomeClass {
                  void doSomething(Mono<String> mono, BiConsumer<String, Throwable> consumer) {
                      mono.doAfterSuccessOrError(consumer).subscribe();
                  }
              }
              """,
            """
              import java.util.function.BiConsumer;

              import reactor.core.observability.DefaultSignalListener;
              import reactor.core.publisher.Mono;
              import reactor.core.publisher.Operators;
              import reactor.core.publisher.SignalType;
              import reactor.util.context.Context;

              class SomeClass {
                  void doSomething(Mono<String> mono, BiConsumer<String, Throwable> consumer) {
                      mono.tap(() -> new DefaultSignalListener<>() {
                          String result;
                          Throwable error;
                          boolean done;
                          boolean processedOnce;
                          Context currentContext;

                          @Override
                          public synchronized void doFinally(SignalType signalType) {
                              if (processedOnce) {
                                  return;
                              }
                              processedOnce = true;
                              if (signalType == SignalType.CANCEL) {
                                  return;
                              }
                              consumer.accept(result, error);
                          }

                          @Override
                          public synchronized void doOnNext(String result) {
                              if (done) {
                                  Operators.onDiscard(result, currentContext);
                                  return;
                              }
                              this.result = result;
                          }

                          @Override
                          public synchronized void doOnComplete() {
                              if (done) {
                                  return;
                              }
                              this.done = true;
                          }

                          @Override
                          public synchronized void doOnError(Throwable error) {
                              if (done) {
                                  Operators.onErrorDropped(error, currentContext);
                              }
                              this.error = error;
                              this.done = true;
                          }

                          @Override
                          public Context addToContext(Context originalContext) {
                              currentContext = originalContext;
                              return originalContext;
                          }

                          @Override
                          public synchronized void doOnCancel() {
                              if (done) return;
                              this.done = true;
                              if (result != null) {
                                  Operators.onDiscard(result, currentContext);
                              }
                          }
                      }).subscribe();
                  }
              }
              """
          )
        );
    }
}
