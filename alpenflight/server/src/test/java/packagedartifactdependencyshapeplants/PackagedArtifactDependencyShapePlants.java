package packagedartifactdependencyshapeplants;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

public final class PackagedArtifactDependencyShapePlants {

    @Component
    public static class PlantedBuilderAsAConstructorParameter {

        private final RestClient restClient;

        public PlantedBuilderAsAConstructorParameter(RestClient.Builder restClientBuilder) {
            this.restClient = restClientBuilder.build();
        }

        public RestClient restClient() {
            return restClient;
        }
    }

    @Component
    public static class PlantedBuilderAsAnAutowiredField {

        @Autowired
        private RestClient.Builder restClientBuilder;

        public RestClient restClient() {
            return restClientBuilder.build();
        }
    }

    @Component
    public static class PlantedBuilderAsAJakartaResourceField {

        @Resource
        private RestClient.Builder restClientBuilder;

        public RestClient restClient() {
            return restClientBuilder.build();
        }
    }

    @Component
    public static class PlantedBuilderThroughAnAutowiredSetter {

        private RestClient restClient = RestClient.create();

        @Autowired
        public void setRestClientBuilder(RestClient.Builder restClientBuilder) {
            this.restClient = restClientBuilder.build();
        }

        public RestClient restClient() {
            return restClient;
        }
    }

    @Configuration
    public static class PlantedBuilderAsABeanMethodParameter {

        @Bean
        public RestClient plantedRestClient(RestClient.Builder restClientBuilder) {
            return restClientBuilder.build();
        }
    }

    @Component
    public static class PlantedBuilderInsideAnObjectProvider {

        private final ObjectProvider<RestClient.Builder> restClientBuilders;

        public PlantedBuilderInsideAnObjectProvider(
                ObjectProvider<RestClient.Builder> restClientBuilders) {
            this.restClientBuilders = restClientBuilders;
        }

        public RestClient restClient() {
            return restClientBuilders.getObject().build();
        }
    }

    @Component
    public static class PlantedClientBuiltInPlaceFromTheStaticFactory {

        private final RestClient restClient;

        public PlantedClientBuiltInPlaceFromTheStaticFactory(String baseUrl) {
            this.restClient = RestClient.create(baseUrl);
        }

        public RestClient restClient() {
            return restClient;
        }
    }

    @Component
    public static class PlantedBuilderAsALocalVariableInsideAMethod {

        public RestClient restClient(String baseUrl) {
            RestClient.Builder restClientBuilder = RestClient.builder().baseUrl(baseUrl);
            return restClientBuilder.build();
        }
    }

    @Component
    public static class PlantedBuilderAsAMethodReturnTypeNotAParameter {

        public RestClient.Builder restClientBuilder(String baseUrl) {
            return RestClient.builder().baseUrl(baseUrl);
        }
    }

    private PackagedArtifactDependencyShapePlants() {
    }
}
