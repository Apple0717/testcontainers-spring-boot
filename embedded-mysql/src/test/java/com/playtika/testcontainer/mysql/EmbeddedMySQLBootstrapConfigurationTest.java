package com.playtika.testcontainer.mysql;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.jdbc.pool.PoolConfiguration;
import org.apache.tomcat.jdbc.pool.PoolProperties;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.ToxiproxyContainer;

import javax.sql.DataSource;

import java.util.concurrent.Callable;

import static com.playtika.testcontainer.mysql.MySQLProperties.BEAN_NAME_EMBEDDED_MYSQL;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest(
        classes = EmbeddedMySQLBootstrapConfigurationTest.TestConfiguration.class,
        properties = {
                "spring.profiles.active=enabled",
                "embedded.mysql.init-script-path=initScript.sql",
                "embedded.toxiproxy.proxies.mysql.enabled=true"
        })
public class EmbeddedMySQLBootstrapConfigurationTest {

    @Autowired
    ConfigurableListableBeanFactory beanFactory;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ConfigurableEnvironment environment;

    @Autowired
    ToxiproxyContainer.ContainerProxy mysqlContainerProxy;

    @Test
    public void shouldConnectToMySQL() {
        assertThat(jdbcTemplate.queryForObject("select version()", String.class)).startsWith("9.1.");
    }

    @Test
    public void shouldSaveAndGetUnicode() {
        jdbcTemplate.execute("CREATE TABLE employee(id INT, name VARCHAR(64));");
        jdbcTemplate.execute("insert into employee (id, name) values (1, 'some data \uD83D\uDE22');");

        assertThat(jdbcTemplate.queryForObject("select name from employee where id = 1", String.class)).isEqualTo("some data \uD83D\uDE22");
    }

    @Test
    public void shouldEmulateLatency() throws Exception {

        jdbcTemplate.execute("CREATE TABLE operator(id INT, name VARCHAR(64));");
        jdbcTemplate.execute("insert into operator (id, name) values (1, 'test');");


        mysqlContainerProxy.toxics().latency("latency", ToxicDirection.UPSTREAM, 1000);

        assertThat(durationOf(() -> jdbcTemplate.queryForList("select name from operator", String.class)))
                .isCloseTo(1000L, Offset.offset(100L));

        mysqlContainerProxy.toxics().get("latency").remove();

        assertThat(durationOf(() -> jdbcTemplate.queryForList("select name from operator", String.class)))
                .isLessThan(100L);
    }

    @Test
    public void shouldInitDBForMySQL() {
        assertThat(jdbcTemplate.queryForObject("select count(first_name) from users where first_name = 'Sam' ", Integer.class)).isEqualTo(1);
    }

    @Test
    public void propertiesAreAvailable() {
        assertThat(environment.getProperty("embedded.mysql.port")).isNotEmpty();
        assertThat(environment.getProperty("embedded.mysql.host")).isNotEmpty();
        assertThat(environment.getProperty("embedded.mysql.schema")).isNotEmpty();
        assertThat(environment.getProperty("embedded.mysql.user")).isNotEmpty();
        assertThat(environment.getProperty("embedded.mysql.password")).isNotEmpty();
        assertThat(environment.getProperty("embedded.mysql.init-script-path")).isNotEmpty();
    }

    @Test
    public void shouldSetupDependsOnForAllDataSources() {
        String[] beanNamesForType = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, DataSource.class);
        assertThat(beanNamesForType)
                .as("Custom datasource should be present")
                .hasSize(1)
                .contains("customDatasource");
        asList(beanNamesForType).forEach(this::hasDependsOn);
    }

    private void hasDependsOn(String beanName) {
        assertThat(beanFactory.getBeanDefinition(beanName).getDependsOn())
                .isNotNull()
                .isNotEmpty()
                .contains(BEAN_NAME_EMBEDDED_MYSQL);
    }

    private static long durationOf(Callable<?> op) throws Exception {
        long start = System.currentTimeMillis();
        op.call();
        return System.currentTimeMillis() - start;
    }

    @EnableAutoConfiguration
    @Configuration
    static class TestConfiguration {

        @Value("${spring.datasource.url}")
        String jdbcUrl;
        @Value("${spring.datasource.username}")
        String user;
        @Value("${spring.datasource.password}")
        String password;

        @Bean(destroyMethod = "close")
        public DataSource customDatasource() {
            PoolConfiguration poolConfiguration = new PoolProperties();
            poolConfiguration.setUrl(jdbcUrl);
            poolConfiguration.setDriverClassName("com.mysql.cj.jdbc.Driver");
            poolConfiguration.setUsername(user);
            poolConfiguration.setPassword(password);
            poolConfiguration.setTestOnBorrow(true);
            poolConfiguration.setTestOnReturn(true);
            return new org.apache.tomcat.jdbc.pool.DataSource(poolConfiguration);
        }
    }
}
