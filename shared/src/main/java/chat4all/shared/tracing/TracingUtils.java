package chat4all.shared.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Tracing Utilities
 * 
 * Helper methods for common tracing operations.
 * 
 * @author Chat4All Team
 * @version 1.0.0
 */
public class TracingUtils {
    
    /**
     * Execute code within a traced span
     * 
     * @param tracer The tracer instance
     * @param spanName Name of the span
     * @param runnable Code to execute
     */
    public static void withSpan(Tracer tracer, String spanName, Runnable runnable) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            runnable.run();
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
    
    /**
     * Execute code within a traced span and return result
     * 
     * @param tracer The tracer instance
     * @param spanName Name of the span
     * @param supplier Code to execute
     * @return Result from supplier
     */
    public static <T> T withSpan(Tracer tracer, String spanName, Supplier<T> supplier) {
        Span span = tracer.spanBuilder(spanName).startSpan();
        try (Scope scope = span.makeCurrent()) {
            return supplier.get();
        } catch (Exception e) {
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
    
    /**
     * Add attributes to current span
     * 
     * @param attributes Map of attributes to add
     */
    public static void addAttributes(Map<String, String> attributes) {
        Span currentSpan = Span.current();
        if (currentSpan != null) {
            attributes.forEach(currentSpan::setAttribute);
        }
    }
    
    /**
     * Add single attribute to current span
     * 
     * @param key Attribute key
     * @param value Attribute value
     */
    public static void addAttribute(String key, String value) {
        Span currentSpan = Span.current();
        if (currentSpan != null) {
            currentSpan.setAttribute(key, value);
        }
    }
    
    /**
     * Add event to current span
     * 
     * @param eventName Name of the event
     */
    public static void addEvent(String eventName) {
        Span currentSpan = Span.current();
        if (currentSpan != null) {
            currentSpan.addEvent(eventName);
        }
    }
    
    /**
     * Get current trace ID
     * 
     * @return Trace ID or empty string if not in span context
     */
    public static String getCurrentTraceId() {
        Span currentSpan = Span.current();
        if (currentSpan != null) {
            return currentSpan.getSpanContext().getTraceId();
        }
        return "";
    }
    
    /**
     * Get current span ID
     * 
     * @return Span ID or empty string if not in span context
     */
    public static String getCurrentSpanId() {
        Span currentSpan = Span.current();
        if (currentSpan != null) {
            return currentSpan.getSpanContext().getSpanId();
        }
        return "";
    }
}
