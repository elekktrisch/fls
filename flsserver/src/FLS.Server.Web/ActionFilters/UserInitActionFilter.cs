using System.Web.Http;
using System.Web.Http.Filters;
using FLS.Server.Data;
using FLS.Server.Service;
using Microsoft.Practices.Unity;

namespace FLS.Server.WebApi.ActionFilters
{
    public class UserInitActionFilter : ActionFilterAttribute
    {
        //[Dependency]
        //public IIdentityService IdentityService { get; set; }

        //[Dependency]
        //public UserService UserService { get; set; }
        
        public override void OnActionExecuting(System.Web.Http.Controllers.HttpActionContext actionContext)
        {
            // Resolve from the per-request DependencyScope, NOT from
            // Configuration.DependencyResolver. The latter is the root
            // resolver, which under Unity's HierarchicalLifetimeManager
            // returns the singleton-in-root instance — meaning every
            // request shares the same IdentityService and concurrent
            // requests overwrite each other's authenticated user.
            // Per-request scope is what Web API + Unity.WebApi sets up
            // automatically via BeginScope.
            var scope = actionContext.Request.GetDependencyScope();
            var identityService = scope.GetService(typeof(IIdentityService)) as IIdentityService;
            var userService = scope.GetService(typeof(UserService)) as UserService;
            var controller = (actionContext.ControllerContext.Controller as ApiController);
            if (controller != null)
            {
                var principal = controller.User;

                if (userService != null && identityService != null)
                {
                    var user = userService.GetUser(principal.Identity.Name);
                    identityService.SetUser(user);
                }
            }

            base.OnActionExecuting(actionContext);
        }
    }
}