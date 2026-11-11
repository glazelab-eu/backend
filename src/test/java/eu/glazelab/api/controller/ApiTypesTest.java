package eu.glazelab.api.controller;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Endpoints exchange explicit records only. Entities would leak fields such as password hashes,
 * trigger lazy loading outside transactions and can recurse through their relations when
 * serialized; self-referencing DTOs would recurse the same way. Times are instants: a zone-less
 * {@code LocalDateTime} would be read in the reader's zone (a date without a time stays a {@code LocalDate}).
 */
class ApiTypesTest {
    private static List<Class<?>> controllers() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> types = new ArrayList<>();
        for (var bean : scanner.findCandidateComponents("eu.glazelab.api")) types.add(Class.forName(bean.getBeanClassName()));
        return types;
    }

    private static boolean isEndpoint(Method method) {
        return java.util.Arrays.stream(method.getAnnotations())
                .anyMatch(a -> a.annotationType().isAnnotationPresent(RequestMapping.class)
                        || a.annotationType() == RequestMapping.class);
    }

    private static boolean isPayload(Parameter parameter) {
        return parameter.isAnnotationPresent(RequestBody.class) || parameter.isAnnotationPresent(RequestPart.class)
                || parameter.isAnnotationPresent(RequestParam.class) || parameter.isAnnotationPresent(PathVariable.class);
    }

    @Test void endpointsUseAcyclicRecordsInsteadOfEntities() throws Exception {
        List<String> problems = new ArrayList<>();
        int endpoints = 0;
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isEndpoint(method)) continue;
                endpoints++;
                String where = controller.getSimpleName() + "." + method.getName();
                inspect(method.getGenericReturnType(), where + " response", new ArrayDeque<>(), problems);
                if (answersMap(method.getGenericReturnType())) problems.add(where + " answers a Map instead of a *View record");
                for (Parameter parameter : method.getParameters()) {
                    if (isPayload(parameter)) inspect(parameter.getParameterizedType(),
                            where + " parameter " + parameter.getName(), new ArrayDeque<>(), problems);
                }
            }
        }
        assertFalse(endpoints < 30, "Expected to inspect every endpoint, found " + endpoints);
        assertEquals(List.of(), problems);
    }

    /** A Map body has no OpenAPI schema; a ResponseEntity is judged by its body type. */
    private static boolean answersMap(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() == org.springframework.http.ResponseEntity.class)
                return answersMap(parameterized.getActualTypeArguments()[0]);
            type = parameterized.getRawType();
        }
        return type instanceof Class<?> raw && java.util.Map.class.isAssignableFrom(raw);
    }

    record LeakingView(eu.glazelab.api.model.AppUser user) {}
    record NodeView(String name, List<NodeView> children) {}
    record LocalTimeView(java.time.LocalDateTime at) {}

    @Test void guardReportsEntitiesAndCycles() throws Exception {
        List<String> problems = new ArrayList<>();
        inspect(LeakingView.class, "leak", new ArrayDeque<>(), problems);
        inspect(ApiTypesTest.class.getDeclaredMethod("nodes").getGenericReturnType(), "cycle", new ArrayDeque<>(), problems);
        inspect(LocalTimeView.class, "time", new ArrayDeque<>(), problems);
        assertEquals(List.of("leak -> LeakingView.user exposes persistence type eu.glazelab.api.model.AppUser",
                "cycle -> NodeView.children has a cyclic type reference through NodeView",
                "time -> LocalTimeView.at uses the zone-less time java.time.LocalDateTime instead of an Instant"), problems);
    }

    @SuppressWarnings("unused") private static java.util.Optional<NodeView> nodes() { return java.util.Optional.empty(); }

    private static void inspect(Type type, String where, Deque<Class<?>> path, List<String> problems) {
        switch (type) {
            case ParameterizedType parameterized -> {
                inspect(parameterized.getRawType(), where, path, problems);
                for (Type argument : parameterized.getActualTypeArguments()) inspect(argument, where, path, problems);
            }
            case WildcardType wildcard -> {
                for (Type bound : wildcard.getUpperBounds()) inspect(bound, where, path, problems);
            }
            case GenericArrayType array -> inspect(array.getGenericComponentType(), where, path, problems);
            case Class<?> raw when raw.isArray() -> inspect(raw.getComponentType(), where, path, problems);
            case Class<?> raw -> {
                if (raw == java.time.LocalDateTime.class) {
                    problems.add(where + " uses the zone-less time " + raw.getName() + " instead of an Instant");
                    return;
                }
                if (raw.isAnnotationPresent(Entity.class) || raw.isAnnotationPresent(Embeddable.class)
                        || raw.getPackageName().startsWith("eu.glazelab.api.model") && !raw.isEnum()) {
                    problems.add(where + " exposes persistence type " + raw.getName());
                    return;
                }
                if (!raw.getPackageName().startsWith("eu.glazelab.api")) return;
                if (path.contains(raw)) {
                    problems.add(where + " has a cyclic type reference through " + raw.getSimpleName());
                    return;
                }
                if (!raw.isRecord() && !raw.isEnum()) {
                    problems.add(where + " uses non-record type " + raw.getName());
                    return;
                }
                if (raw.isEnum()) return;
                path.push(raw);
                for (RecordComponent component : raw.getRecordComponents())
                    inspect(component.getGenericType(), where + " -> " + raw.getSimpleName() + "." + component.getName(),
                            path, problems);
                path.pop();
            }
            default -> problems.add(where + " uses unsupported type " + type);
        }
    }
}
