package com.smd.ordertrackingservice.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** The table the service creates must be the reviewed one in data-model/. */
class TableDefinitionTest {

    @Test
    void classpathDefinitionMatchesTheDataModel() throws Exception {
        // Gradle runs tests from the module folder: domains/delivery/order-tracking-service
        String reviewed = Files.readString(Path.of("../../../data-model/dynamodb/order_tracking.table.json"));
        String packaged = new String(new ClassPathResource(DynamoDbTableInitializer.DEFINITION).getInputStream().readAllBytes());

        assertThat(packaged).isEqualTo(reviewed);
    }
}
