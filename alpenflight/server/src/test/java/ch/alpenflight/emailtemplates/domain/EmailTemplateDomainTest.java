package ch.alpenflight.emailtemplates.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailTemplateDomainTest {

    @Test
    void customize_canonicalizesKeyAndLocaleLowerCaseAndTrims() {
        EmailTemplate t = EmailTemplate.customize(
                "  LostPassword  ", "  DE  ", "  Reset your password  ", "  <p>Hi</p>  ");
        assertThat(t.getTemplateKey()).isEqualTo("lostpassword");
        assertThat(t.getLanguageLocale()).isEqualTo("de");
        assertThat(t.getSubject()).isEqualTo("Reset your password");
        assertThat(t.getBody()).isEqualTo("<p>Hi</p>");
    }

    @Test
    void customize_blankKeyLocaleSubjectOrBody_rejects() {
        assertThatThrownBy(() -> EmailTemplate.customize("  ", "de", "s", "b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("templateKey");
        assertThatThrownBy(() -> EmailTemplate.customize("k", "  ", "s", "b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("languageLocale");
        assertThatThrownBy(() -> EmailTemplate.customize("k", "de", "  ", "b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
        assertThatThrownBy(() -> EmailTemplate.customize("k", "de", "s", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body");
    }

    @Test
    void revise_updatesContent_keepsCanonicalIdentity() {
        EmailTemplate t = EmailTemplate.customize("planningday-ok", "de", "Old subject", "Old body");
        t.revise("  New subject  ", "  New body  ");
        assertThat(t.getSubject()).isEqualTo("New subject");
        assertThat(t.getBody()).isEqualTo("New body");
        assertThat(t.getTemplateKey()).isEqualTo("planningday-ok");
        assertThat(t.getLanguageLocale()).isEqualTo("de");

        assertThatThrownBy(() -> t.revise("  ", "b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");
        assertThatThrownBy(() -> t.revise("s", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body");
    }
}
