using Microsoft.Owin.Security.DataHandler;
using Microsoft.Owin.Security.DataProtection;
using Microsoft.Owin.Security.OAuth;
using Owin;
using FLS.Server.WebApi;

namespace FLS.Server.MonoHost
{
    // Self-host startup wrapper: installs a Mono-safe IDataProtectionProvider before
    // the real Startup runs, then delegates to it. Without this, OAuth bearer
    // middleware tries to TypeLoad Windows-only DpapiDataProtector and crashes.
    public class MonoStartup
    {
        public void Configuration(IAppBuilder app)
        {
            System.Console.WriteLine("MonoStartup.Configuration called");
            var provider = new SimpleDataProtectionProvider();
            app.SetDataProtectionProvider(provider);
            System.Console.WriteLine("SetDataProtectionProvider done");

            // Pre-set the OAuth token format so DpapiDataProtector is never touched.
            var protector = provider.Create("Microsoft.Owin.Security.OAuth", "Access_Token", "v1");
            Startup.OAuthOptions.AccessTokenFormat = new TicketDataFormat(protector);
            System.Console.WriteLine("OAuthOptions.AccessTokenFormat set to " + Startup.OAuthOptions.AccessTokenFormat);

            new Startup().Configuration(app);
        }
    }

    // Adapts our raw byte[] IDataProtector to the SecureDataFormat<TData> world TicketDataFormat
    // works with. TicketDataFormat<AuthenticationTicket>(IDataProtector) takes IDataProtector
    // directly, so this adapter is redundant — but kept as a stub in case wrapping is needed.
    internal class SecureDataFormatAdapter : IDataProtector
    {
        private readonly IDataProtector _inner;
        public SecureDataFormatAdapter(IDataProtector inner) { _inner = inner; }
        public byte[] Protect(byte[] userData) { return _inner.Protect(userData); }
        public byte[] Unprotect(byte[] protectedData) { return _inner.Unprotect(protectedData); }
    }
}
