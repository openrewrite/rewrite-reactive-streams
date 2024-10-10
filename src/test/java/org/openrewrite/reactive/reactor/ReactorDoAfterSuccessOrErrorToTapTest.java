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
                void doSomething(Mono<String> mono){
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
            import reactor.core.publisher.SignalType;
            
            class SomeClass {
                void doSomething(Mono<String> mono) {
                    mono.tap(() -> new DefaultSignalListener<>() {
                                @Override
                                public void doFinally(SignalType terminationType) {
                                    System.out.println("other logs");
                                }
            
                                @Override
                                public void doOnNext(String result) {
                                    System.out.println("success" + result);
                                }
            
                                @Override
                                public void doOnError(Throwable error) {
                                    System.out.println("error" + error);
                                }
                            }
                    ).subscribe();
                }
            }
            """
          )
        );
    }

    @Test
    void refactorRandomStatementOrder() {
        //language=java
        rewriteRun(
          java(
            """
            import reactor.core.publisher.Mono;

            class SomeClass {
                void doSomething(Mono<String> mono){
                    mono.doAfterSuccessOrError((result, error) -> {
                        doSomething();
                        if (error != null) {
                            System.out.println("error" + error);
                            doSomething(error);
                        } else {
                            System.out.println("success" + result);
                            doSomething(result);
                        }
                        System.out.println("other logs");
                    }).subscribe();
                }

                void doSomething(){
                }

                void doSomething(Throwable error){
                }

                void doSomething(String value){
                }
            }
            """,
            """
            import reactor.core.observability.DefaultSignalListener;
            import reactor.core.publisher.Mono;
            import reactor.core.publisher.SignalType;
            
            class SomeClass {
                void doSomething(Mono<String> mono) {
                    mono.tap(() -> new DefaultSignalListener<>() {
                                @Override
                                public void doFinally(SignalType terminationType) {
                                    doSomething();
                                    System.out.println("other logs");
                                }
            
                                @Override
                                public void doOnNext(String result) {
                                    System.out.println("success" + result);
                                    doSomething(result);
                                }
            
                                @Override
                                public void doOnError(Throwable error) {
                                    System.out.println("error" + error);
                                    doSomething(error);
                                }
                            }
                    ).subscribe();
                }

                void doSomething(){
                }

                void doSomething(Throwable error){
                }

                void doSomething(String value){
                }
            }
            """
          )
        );
    }

    @Test
    void invertedIfCheck() {
        //language=java
        rewriteRun(
          java(
            """
            import reactor.core.publisher.Mono;
            
            class SomeClass {
                void doSomething(Mono<String> mono){
                    mono.doAfterSuccessOrError((result, error) -> {
                        if (error == null) {
                            System.out.println("success" + result);
                        }
                        if (null == result) {
                            System.out.println("error" + error);
                        }
                        System.out.println("other logs");
                    }).subscribe();
                }
            }
            """,
            """
            import reactor.core.observability.DefaultSignalListener;
            import reactor.core.publisher.Mono;
            import reactor.core.publisher.SignalType;
            
            class SomeClass {
                void doSomething(Mono<String> mono) {
                    mono.tap(() -> new DefaultSignalListener<>() {
                                @Override
                                public void doFinally(SignalType terminationType) {
                                    System.out.println("other logs");
                                }
            
                                @Override
                                public void doOnNext(String result) {
                                    System.out.println("success" + result);
                                }
            
                                @Override
                                public void doOnError(Throwable error) {
                                    System.out.println("error" + error);
                                }
                            }
                    ).subscribe();
                }
            }
            """
          )
        );
    }

    @Test
    void multipleIfNoElse() {
        //language=java
        rewriteRun(
          java(
            """
            import reactor.core.publisher.Mono;
            
            class SomeClass {
                void doSomething(Mono<String> mono){
                    mono.doAfterSuccessOrError((result, error) -> {
                        if (error != null) {
                            System.out.println("error" + error);
                        }
                        if (result != null){
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
            import reactor.core.publisher.SignalType;
            
            class SomeClass {
                void doSomething(Mono<String> mono) {
                    mono.tap(() -> new DefaultSignalListener<>() {
                                @Override
                                public void doFinally(SignalType terminationType) {
                                    System.out.println("other logs");
                                }
            
                                @Override
                                public void doOnNext(String result) {
                                    System.out.println("success" + result);
                                }
            
                                @Override
                                public void doOnError(Throwable error) {
                                    System.out.println("error" + error);
                                }
                            }
                    ).subscribe();
                }
            }
            """
          )
        );
    }

    @Test
    void multipleIfNoElseRandomStatementOrder() {
        //language=java
        rewriteRun(
          java(
            """
            import reactor.core.publisher.Mono;

            class SomeClass {
                void doSomething(Mono<String> mono){
                    mono.doAfterSuccessOrError((result, error) -> {
                        System.out.println("other logs");
                        if (error != null) {
                            System.out.println("error" + error);
                            doSomething(error);
                        }
                        doSomething();
                        if (result != null){
                            System.out.println("success" + result);
                            doSomething(result);
                        }
                    }).subscribe();
                }

                void doSomething(){
                }

                void doSomething(Throwable error){
                }

                void doSomething(String value){
                }
            }
            """,
            """
            import reactor.core.observability.DefaultSignalListener;
            import reactor.core.publisher.Mono;
            import reactor.core.publisher.SignalType;
            
            class SomeClass {
                void doSomething(Mono<String> mono) {
                    mono.tap(() -> new DefaultSignalListener<>() {
                                @Override
                                public void doFinally(SignalType terminationType) {
                                    System.out.println("other logs");
                                    doSomething();
                                }
            
                                @Override
                                public void doOnNext(String result) {
                                    System.out.println("success" + result);
                                    doSomething(result);
                                }
            
                                @Override
                                public void doOnError(Throwable error) {
                                    System.out.println("error" + error);
                                    doSomething(error);
                                }
                            }
                    ).subscribe();
                }

                void doSomething(){
                }

                void doSomething(Throwable error){
                }

                void doSomething(String value){
                }
            }
            """
          )
        );
    }

    @Test
    void singleIfNoElse() {
        //language=java
        rewriteRun(
          java(
            """
            import reactor.core.publisher.Mono;
            
            class SomeClass {
                void doSomething(Mono<String> mono){
                    mono.doAfterSuccessOrError((result, error) -> {
                        if (error != null) {
                            System.out.println("error" + error);
                        }
                        System.out.println("success" + result);
                        System.out.println("other logs");
                    }).subscribe();
                }
            }
            """,
            """
            import reactor.core.observability.DefaultSignalListener;
            import reactor.core.publisher.Mono;
            import reactor.core.publisher.SignalType;
            
            class SomeClass {
                void doSomething(Mono<String> mono) {
                    mono.tap(() -> new DefaultSignalListener<>() {
                                @Override
                                public void doFinally(SignalType terminationType) {
                                    System.out.println("other logs");
                                }
            
                                @Override
                                public void doOnNext(String result) {
                                    System.out.println("success" + result);
                                }
            
                                @Override
                                public void doOnError(Throwable error) {
                                    System.out.println("error" + error);
                                }
                            }
                    ).subscribe();
                }
            }
            """
          )
        );
    }
}
