using System.Runtime.Remoting.Messaging;
using FLS.Server.Data;
using FLS.Server.Data.DbEntities;

namespace FLS.Server.Service
{
    public class IdentityService : IIdentityService
    {
        // Per-request isolation via CallContext.LogicalGetData/SetData
        // (the .NET 4.5 equivalent of AsyncLocal — AsyncLocal itself is
        // .NET 4.6+, and the projects target 4.5). LogicalSetData flows
        // values along the async/await chain of the current request, so
        // one request's SetUser cannot stomp another's, regardless of
        // how Unity's DI scoping is configured.
        //
        // Why bother: the IdentityService instance is registered with
        // HierarchicalLifetimeManager, intended to be per-request. But
        // the actual per-request scoping depends on
        // UnityHierarchicalDependencyResolver + Web API's BeginScope(),
        // both of which we've seen fail to isolate requests under some
        // configurations. CallContext bypasses Unity scoping entirely.
        private const string LogicalKey = "FLS.Server.Service.IdentityService.User";

        public IdentityService()
        {
        }

        public User CurrentAuthenticatedFLSUser
        {
            get { return CallContext.LogicalGetData(LogicalKey) as User; }
        }

        public void SetUser(User user)
        {
            CallContext.LogicalSetData(LogicalKey, user);
        }
    }
}
