<#--
  Overrides keycloak.v2's empty footer macro to inject a "Back to Start"
  link below the login form. The link reads the OIDC client's `baseUrl`
  attribute on the `alpenflight-web` client; the value lands via the
  Dockerfile build-arg substitution of `${ALPENFLIGHT_WEB_BASE_URL}` in
  realm-export.json (see alpenflight/auth/README.md § Substitution layers
  — Keycloak's own realm-import substitution doesn't reach client.baseUrl
  because the URL validator runs before substitution).

  No FreeMarker fallback — the build-arg sed + check-realm-shape.sh shape
  guard + docker-compose `${VAR:-default}` block collectively close every
  "client.baseUrl unset" path. If the link raises in production, the
  deploy env didn't set ALPENFLIGHT_WEB_BASE_URL.

  Rendered inside `.pf-v5-c-login__main-footer` from Keycloak's v2
  template.ftl, below the social-providers + info bands. Styling lives in
  `alpenflight/login/resources/css/login.css` under `.af-back-to-landing`.
-->
<#macro content>
  <div class="af-back-to-landing-band">
    <a class="af-back-to-landing" href="${client.baseUrl}">
      ${msg('backToLanding')}
    </a>
  </div>
</#macro>
