package io.clusterplatform;

import io.clusterplatform.api.RequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AuthorizationTest {
    @Test void unknownSubjectAndReadOnlyScopeCannotWrite() {
        var context=new RequestContext(new MockEnvironment().withProperty("platform.subject","known").withProperty("platform.project","project-a"));
        var read=new UsernamePasswordAuthenticationToken("known",null,List.of(new SimpleGrantedAuthority("SCOPE_platform.read")));
        assertThat(context.project(read,false)).isEqualTo("project-a");
        assertThatThrownBy(()->context.project(read,true)).hasMessage("insufficient_scope");
        var other=new UsernamePasswordAuthenticationToken("other",null,List.of(new SimpleGrantedAuthority("SCOPE_platform.write")));
        assertThatThrownBy(()->context.project(other,true)).hasMessage("caller_not_mapped");
    }
}
