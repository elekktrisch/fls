<#macro content>
  <#assign theAlpenflightBaseUrl = (client.baseUrl)!''>
  <#if theAlpenflightBaseUrl?has_content>
    <#assign theAlpenflightOrigin = theAlpenflightBaseUrl?remove_ending('/')>
    <#assign thisPageReportsKeycloakVerifiedTheEmailAddress =
      ((message.summary)!'') == msg('emailVerifiedMessage')
      || ((message.summary)!'') == msg('emailVerifiedAlreadyMessage')>
    <#assign thisPageReportsTheEmailActionLinkIsSpent =
      ((message.summary)!'') == msg('expiredActionMessage')
      || ((message.summary)!'') == msg('expiredActionTokenNoSessionMessage')
      || ((message.summary)!'') == msg('expiredActionTokenSessionExistsMessage')>
    <#if thisPageReportsKeycloakVerifiedTheEmailAddress>
      <#assign theBackLinkTarget = theAlpenflightOrigin + '/confirm?outcome=verified'>
      <#assign theBackLinkText = msg('backToEmailConfirmation')>
    <#elseif thisPageReportsTheEmailActionLinkIsSpent>
      <#assign theBackLinkTarget = theAlpenflightOrigin + '/lostpassword'>
      <#assign theBackLinkText = msg('backToPasswordRecovery')>
    <#else>
      <#assign theBackLinkTarget = theAlpenflightBaseUrl>
      <#assign theBackLinkText = msg('backToLanding')>
    </#if>
    <div class="af-back-to-landing-band">
      <a class="af-back-to-landing" href="${theBackLinkTarget}">
        ${theBackLinkText}
      </a>
    </div>
  </#if>
</#macro>
