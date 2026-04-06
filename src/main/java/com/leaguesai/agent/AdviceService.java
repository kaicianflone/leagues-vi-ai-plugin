package com.leaguesai.agent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

@Singleton
public class AdviceService {

    private final LlmClient openAiClient;
    private final PlayerContextAssembler contextAssembler;

    @Inject
    public AdviceService(LlmClient openAiClient, PlayerContextAssembler contextAssembler) {
        this.openAiClient = openAiClient;
        this.contextAssembler = contextAssembler;
    }

    /**
     * Assemble the current player context, build prompts, and request a concise
     * advice response from the AI.
     *
     * Thread-safe by construction: no mutable instance state; each call operates
     * entirely on local variables.
     */
    public String getAdvice() throws Exception {
        PlayerContext ctx = contextAssembler.assemble();
        String systemPrompt = PromptBuilder.buildSystemPrompt(ctx);
        String advicePrompt = PromptBuilder.buildAdvicePrompt();

        return openAiClient.chatCompletion(
                systemPrompt,
                List.of(new OpenAiClient.Message("user", advicePrompt))
        );
    }
}
