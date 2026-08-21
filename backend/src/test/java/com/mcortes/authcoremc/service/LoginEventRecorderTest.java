package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mcortes.authcoremc.domain.LoginEvent;
import com.mcortes.authcoremc.domain.LoginOutcome;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.LoginEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LoginEventRecorderTest {

    private final LoginEventRepository repository = mock(LoginEventRepository.class);
    private final LoginEventRecorder recorder = new LoginEventRecorder(repository);

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
    private final User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");

    @Test
    void recordSuccessSavesASuccessEventWithTheUser() {
        recorder.recordSuccess(tenant, user, "PASSWORD", 42);

        ArgumentCaptor<LoginEvent> captor = ArgumentCaptor.forClass(LoginEvent.class);
        verify(repository).save(captor.capture());
        LoginEvent saved = captor.getValue();
        assertThat(saved.getOutcome()).isEqualTo(LoginOutcome.SUCCESS);
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getProvider()).isEqualTo("PASSWORD");
        assertThat(saved.getLatencyMs()).isEqualTo(42);
        assertThat(saved.getTenant()).isSameAs(tenant);
    }

    @Test
    void recordFailureSavesAFailureEventWithNoUser() {
        recorder.recordFailure(tenant, "PASSWORD", 15);

        ArgumentCaptor<LoginEvent> captor = ArgumentCaptor.forClass(LoginEvent.class);
        verify(repository).save(captor.capture());
        LoginEvent saved = captor.getValue();
        assertThat(saved.getOutcome()).isEqualTo(LoginOutcome.FAILURE);
        assertThat(saved.getUser()).isNull();
        assertThat(saved.getLatencyMs()).isEqualTo(15);
    }

    @Test
    void aRepositoryFailureNeverPropagates() {
        doThrow(new RuntimeException("db is down")).when(repository).save(any());

        assertThatCode(() -> recorder.recordSuccess(tenant, user, "PASSWORD", 10)).doesNotThrowAnyException();
        assertThatCode(() -> recorder.recordFailure(tenant, "PASSWORD", 10)).doesNotThrowAnyException();
    }
}
