package fr.tikigaming.games.tic_tac_toe;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TicTacToeApiConfiguration {

    /**
     * Groupe OpenAPI dédié au jeu : {@code /v3/api-docs/tic-tac-toe}. C'est la cible du scan DAST ZAP du jeu
     * (job CI `dast`), en plus du scan global {@code /v3/api-docs}.
     */
    @Bean
    GroupedOpenApi ticTacToeApi() {
        return GroupedOpenApi.builder()
                .group("tic-tac-toe")
                .pathsToMatch("/api/games/tic-tac-toe/**")
                .build();
    }
}
