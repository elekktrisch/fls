using System;
using System.Threading;
using Microsoft.Owin.Hosting;

namespace FLS.Server.MonoHost
{
    public class Program
    {
        public static void Main(string[] args)
        {
            string url = Environment.GetEnvironmentVariable("FLS_LISTEN_URL");
            if (string.IsNullOrEmpty(url)) url = "http://localhost:25567/";

            var done = new ManualResetEventSlim();
            System.Console.CancelKeyPress += (s, e) => { e.Cancel = true; done.Set(); };

            System.Console.WriteLine("FLS Server starting on " + url);
            try
            {
                using (WebApp.Start<MonoStartup>(url))
                {
                    System.Console.WriteLine("FLS Server READY on " + url);
                    System.Console.WriteLine("Press Ctrl+C to stop");
                    done.Wait();
                }
                System.Console.WriteLine("FLS Server stopped");
            }
            catch (Exception ex)
            {
                System.Console.Error.WriteLine("STARTUP FAILED: " + ex);
                Environment.Exit(1);
            }
        }
    }
}
