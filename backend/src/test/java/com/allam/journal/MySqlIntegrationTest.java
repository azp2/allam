package com.allam.journal;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
/** Executes the same lifecycle/privacy tests against the production database dialect. */
@Testcontainers(disabledWithoutDocker=true)
class MySqlIntegrationTest extends WorkflowIntegrationTest {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4").withDatabaseName("journal");
 @DynamicPropertySource static void mysql(DynamicPropertyRegistry properties) {
  properties.add("spring.datasource.url",MYSQL::getJdbcUrl);properties.add("spring.datasource.username",MYSQL::getUsername);properties.add("spring.datasource.password",MYSQL::getPassword);properties.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
 }
}
