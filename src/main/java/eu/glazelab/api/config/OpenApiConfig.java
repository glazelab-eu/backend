package eu.glazelab.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean OpenAPI openApi() {
        return new OpenAPI().info(new Info().title("Glazelab API").version("1"))
                .servers(List.of(new Server().url("/")));
    }

    /**
     * OpenAPI 3.1 lets a nullable enum ({@code type: [string, null]}) accept null only when the enum
     * lists it, so generated clients type the value as nullable.
     */
    @SuppressWarnings("unchecked")
    private static void nullableEnum(Schema<?> property) {
        var values = (List<Object>) property.getEnum();
        if (values != null && property.getTypes() != null && property.getTypes().contains("null")
                && !values.contains(null))
            values.add(null);
    }

    @Bean OpenApiCustomizer securityEndpoints() {
        return api -> {
            var login = new ObjectSchema().addProperty("email", new StringSchema().format("email"))
                    .addProperty("password", new StringSchema()).required(List.of("email", "password"));
            api.path("/api/auth/login", new PathItem().post(new Operation().operationId("login")
                    .summary("Sign in using the session cookie; obtain a CSRF token before submitting")
                    .requestBody(new RequestBody().required(true).content(new Content()
                            .addMediaType("application/x-www-form-urlencoded", new MediaType().schema(login))))
                    .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("Signed in"))
                            .addApiResponse("401", new ApiResponse().description("Invalid credentials")))));
            api.path("/api/auth/logout", new PathItem().post(new Operation().operationId("logout")
                    .summary("Sign out; requires a fresh CSRF token")
                    .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("Signed out")))));
            ErrorResponses.document(api);
            // Response DTOs emit all their fields; optional request fields remain optional.
            api.getComponents().getSchemas().forEach((name, schema) -> {
                if (name.endsWith("View") && schema.getProperties() != null)
                    schema.setRequired(List.copyOf(schema.getProperties().keySet()));
                if (schema.getProperties() != null)
                    schema.getProperties().values().forEach(property -> nullableEnum((Schema<?>) property));
            });
        };
    }
}
