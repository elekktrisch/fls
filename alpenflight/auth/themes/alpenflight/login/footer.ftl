<#--
  Overrides keycloak.v2's empty footer macro to inject a "Back to Start"
  link below the login form (the login page is part of AlpenFlight from
  the user's perspective, so the label is route-relative, not brand-
  scoped). The link reads the OIDC client's `baseUrl` attribute (set on
  the `alpenflight-web` client) and falls back to the dev default if
  unset — production deployments should set baseUrl on the client so
  this resolves correctly per-environment.

  Rendered inside `.pf-v5-c-login__main-footer` (see template.ftl line
  258), below the social-providers + info bands.

  Styling lives in `alpenflight/login/resources/css/login.css` under
  `.af-back-to-landing` — link color = brand-500, hover = brand-600,
  matches the SPA's link convention.
-->
<#macro content>
  <div class="af-back-to-landing-band">
    <a class="af-back-to-landing" href="${client.baseUrl!'http://localhost:4200/'}">
      &larr; ${msg('backToLanding')}
    </a>
  </div>
</#macro>
