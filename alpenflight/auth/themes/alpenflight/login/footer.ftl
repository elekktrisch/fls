<#--
  Overrides keycloak.v2's empty footer macro to inject a "Back to Start"
  link below the login form. The link reads the OIDC client's `baseUrl`
  attribute (set on the `alpenflight-web` client via the
  ${env:ALPENFLIGHT_WEB_BASE_URL} substitution in realm-export.json).

  No FreeMarker fallback — the realm-export env-substitution +
  check-realm-shape.sh shape guard + docker-compose ${VAR:-default} block
  collectively close every "client.baseUrl unset" path. If the link
  raises in production, the deploy env didn't set ALPENFLIGHT_WEB_BASE_URL.

  Rendered inside `.pf-v5-c-login__main-footer` (see template.ftl line
  258), below the social-providers + info bands. Styling lives in
  `alpenflight/login/resources/css/login.css` under `.af-back-to-landing`.
-->
<#macro content>
  <div class="af-back-to-landing-band">
    <a class="af-back-to-landing" href="${client.baseUrl}">
      ${msg('backToLanding')}
    </a>
  </div>
</#macro>
