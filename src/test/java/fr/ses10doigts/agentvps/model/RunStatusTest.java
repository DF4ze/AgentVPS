package fr.ses10doigts.agentvps.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunStatusTest {

    @Test
    void mapsNagiosStyleExitCodesToStatuses() {
        assertThat(RunStatus.fromExitCode(0)).isEqualTo(RunStatus.OK);
        assertThat(RunStatus.fromExitCode(1)).isEqualTo(RunStatus.WARNING);
        assertThat(RunStatus.fromExitCode(2)).isEqualTo(RunStatus.CRITICAL);
    }

    @Test
    void mapsAnyOtherExitCodeToUnknown() {
        assertThat(RunStatus.fromExitCode(3)).isEqualTo(RunStatus.UNKNOWN);
        assertThat(RunStatus.fromExitCode(127)).isEqualTo(RunStatus.UNKNOWN);
        assertThat(RunStatus.fromExitCode(-1)).isEqualTo(RunStatus.UNKNOWN);
    }

    @Test
    void onlyOkIsNotAnIssue() {
        assertThat(RunStatus.OK.isIssue()).isFalse();
        assertThat(RunStatus.WARNING.isIssue()).isTrue();
        assertThat(RunStatus.CRITICAL.isIssue()).isTrue();
        assertThat(RunStatus.UNKNOWN.isIssue()).isTrue();
        assertThat(RunStatus.ERROR.isIssue()).isTrue();
    }
}
