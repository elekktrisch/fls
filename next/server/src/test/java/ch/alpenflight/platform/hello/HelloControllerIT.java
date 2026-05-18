package ch.alpenflight.platform.hello;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

// addFilters = false bypasses Spring Security in the slice — S-048 added the
// starter and a default-deny chain, but /api/v1/hello is on the permit-list
// of both the default and mock-auth chains, so production behavior is
// unchanged. Disabling filters keeps the slice from autoconfiguring an
// alternative chain and short-circuiting the assertion.
@WebMvcTest(HelloController.class)
@AutoConfigureMockMvc(addFilters = false)
class HelloControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void helloEndpointReturns200WithExpectedBody() throws Exception {
        mockMvc.perform(get("/api/v1/hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello AlpenFlight"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
