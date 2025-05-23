package io.quarkus.arc.runtime.dev.console;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Intercepted;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.ManagedContext;
import io.quarkus.arc.runtime.dev.console.Invocation.Builder;
import io.quarkus.arc.runtime.dev.console.Invocation.Kind;

@Priority(Interceptor.Priority.LIBRARY_BEFORE)
@Monitored
@Interceptor
public class InvocationInterceptor {

    @Inject
    InvocationsMonitor invocationMonitor;

    @Intercepted
    InjectableBean<?> bean;

    @Inject
    Instance<InvocationTree> invocationTree;

    @AroundInvoke
    public Object monitor(InvocationContext context) throws Exception {
        ArcContainer container = Arc.container();
        if (container == null) {
            // If the container is not available then just proceed
            return context.proceed();
        }
        ManagedContext requestContext = container.requestContext();
        if (requestContext.isActive()) {
            InvocationTree tree = invocationTree.get();
            return proceed(tree.invocationStarted(bean, context.getMethod(), getKind(context)), context, requestContext, tree);
        } else {
            return proceed(
                    new Builder().setInterceptedBean(bean).setMethod(context.getMethod()).setKind(getKind(context))
                            .setStart(System.currentTimeMillis()),
                    context, requestContext, null);
        }
    }

    Object proceed(Invocation.Builder builder, InvocationContext context, ManagedContext requestContext, InvocationTree tree)
            throws Exception {
        long nanoTime = System.nanoTime();
        try {
            return context.proceed();
        } catch (Exception e) {
            builder.setMessage(e.getMessage());
            throw e;
        } finally {
            builder.setDuration(System.nanoTime() - nanoTime);
            if (builder.getParent() == null) {
                // Flush the data about a top-level invocation
                invocationMonitor.addInvocation(builder.build());
            }
            if (tree != null && requestContext.isActive()) {
                tree.invocationCompleted();
            }
        }
    }

    Invocation.Kind getKind(InvocationContext ctx) {
        // This will only work for "unmodified" discovered types
        Method method = ctx.getMethod();
        if (!method.getReturnType().equals(Void.TYPE) && method.isAnnotationPresent(Produces.class)) {
            return Kind.PRODUCER;
        } else if (method.getParameterCount() > 0) {
            Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            if (parameterAnnotations.length > 0) {
                for (Annotation[] annotations : parameterAnnotations) {
                    for (Annotation annotation : annotations) {
                        Class<? extends Annotation> type = annotation.annotationType();
                        if (Observes.class.equals(type) || ObservesAsync.class.equals(type)) {
                            return Kind.OBSERVER;
                        } else if (Disposes.class.equals(type)) {
                            return Kind.DISPOSER;
                        }
                    }
                }
            }
        }
        return Kind.BUSINESS;
    }

}
