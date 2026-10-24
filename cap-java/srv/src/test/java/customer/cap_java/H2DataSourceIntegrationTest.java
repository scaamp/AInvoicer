package customer.cap_java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class H2DataSourceIntegrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void executesSelectOneAgainstConfiguredDataSource() {
		assertEquals(1, jdbcTemplate.queryForObject("SELECT 1", Integer.class));
	}
}
