package io.github.venkat1701.pipeline.nodes;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.venkat1701.core.contracts.LLMClient;
import io.github.venkat1701.core.enums.OutputFormat;
import io.github.venkat1701.core.enums.ReasoningMethod;
import io.github.venkat1701.pipeline.graph.GraphNode;
import io.github.venkat1701.pipeline.models.QueryAnalysis;
import io.github.venkat1701.pipeline.profile.UserProfile;
import io.github.venkat1701.pipeline.state.ResearchAgentState;

public class ReasoningSelectionNode implements GraphNode<ResearchAgentState> {

    private final LLMClient llmClient;

    public ReasoningSelectionNode(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public CompletableFuture<ResearchAgentState> process(ResearchAgentState state) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ReasoningMethod selectedMethod = selectOptimalReasoning(state);
                return state.withReasoning(selectedMethod);
            } catch (Exception e) {
                return state.withReasoning(ReasoningMethod.CHAIN_OF_THOUGHT);
            }
        });
    }

    private ReasoningMethod selectOptimalReasoning(ResearchAgentState state) {
        QueryAnalysis analysis = (QueryAnalysis) state.getMetadata()
                .get("query_analysis");
        UserProfile profile = state.getUserProfile();
        String query = state.getQuery() != null ? state.getQuery()
                .toLowerCase() : "";

        Map<ReasoningMethod, Integer> scores = new HashMap<>();
        scores.put(ReasoningMethod.CHAIN_OF_THOUGHT, 10);
        scores.put(ReasoningMethod.CHAIN_OF_IDEAS, 10);
        scores.put(ReasoningMethod.CHAIN_OF_TABLE, 10);

        // Use llmClient's model name to influence reasoning selection
        try {
            String modelName = null;
            if (llmClient != null) {
                // Try reflection to get modelName if not in interface
                try {
                    java.lang.reflect.Method m = llmClient.getClass().getMethod("getModelName");
                    Object result = m.invoke(llmClient);
                    if (result != null)
                        modelName = result.toString().toLowerCase();
                } catch (Exception ignored) {
                }
            }
            if (modelName != null) {
                if (modelName.contains("gpt")) {
                    scores.put(ReasoningMethod.CHAIN_OF_IDEAS, scores.get(ReasoningMethod.CHAIN_OF_IDEAS) + 10);
                } else if (modelName.contains("gemini")) {
                    scores.put(ReasoningMethod.CHAIN_OF_THOUGHT, scores.get(ReasoningMethod.CHAIN_OF_THOUGHT) + 10);
                }
            }
        } catch (Exception ignored) {
        }

        if (analysis != null && analysis.intent != null) {
            switch (analysis.intent) {
                case "comparison" ->
                    scores.put(ReasoningMethod.CHAIN_OF_TABLE, scores.get(ReasoningMethod.CHAIN_OF_TABLE) + 30);
                case "creative" ->
                    scores.put(ReasoningMethod.CHAIN_OF_IDEAS, scores.get(ReasoningMethod.CHAIN_OF_IDEAS) + 30);
                case "analysis", "research" ->
                    scores.put(ReasoningMethod.CHAIN_OF_THOUGHT, scores.get(ReasoningMethod.CHAIN_OF_THOUGHT) + 30);
            }
        }

        if (query.contains("compare") || query.contains("versus") || query.contains("difference")) {
            scores.put(ReasoningMethod.CHAIN_OF_TABLE, scores.get(ReasoningMethod.CHAIN_OF_TABLE) + 20);
        }
        if (query.contains("creative") || query.contains("idea") || query.contains("brainstorm")) {
            scores.put(ReasoningMethod.CHAIN_OF_IDEAS, scores.get(ReasoningMethod.CHAIN_OF_IDEAS) + 20);
        }
        if (query.contains("analyze") || query.contains("explain") || query.contains("why")) {
            scores.put(ReasoningMethod.CHAIN_OF_THOUGHT, scores.get(ReasoningMethod.CHAIN_OF_THOUGHT) + 20);
        }

        if (profile != null) {
            if (profile.getPreferences() != null && profile.hasPreference("detailed")) {
                scores.put(ReasoningMethod.CHAIN_OF_THOUGHT, scores.get(ReasoningMethod.CHAIN_OF_THOUGHT) + 15);
            }
            if ((profile.getPreferences() != null && profile.hasPreference("visual"))
                    || profile.getPreferredFormat() == OutputFormat.TABLE) {
                scores.put(ReasoningMethod.CHAIN_OF_TABLE, scores.get(ReasoningMethod.CHAIN_OF_TABLE) + 15);
            }
            if (profile.getDomain() != null) {
                switch (profile.getDomain()) {
                    case "business" ->
                        scores.put(ReasoningMethod.CHAIN_OF_TABLE, scores.get(ReasoningMethod.CHAIN_OF_TABLE) + 10);
                    case "academic" ->
                        scores.put(ReasoningMethod.CHAIN_OF_THOUGHT, scores.get(ReasoningMethod.CHAIN_OF_THOUGHT) + 10);
                    case "creative" ->
                        scores.put(ReasoningMethod.CHAIN_OF_IDEAS, scores.get(ReasoningMethod.CHAIN_OF_IDEAS) + 10);
                }
            }
        }

        return scores.entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(ReasoningMethod.CHAIN_OF_THOUGHT);
    }

    @Override
    public String getName() {
        return "reasoning_selection";
    }

    @Override
    public boolean shouldExecute(ResearchAgentState state) {
        return state != null && !state.isComplete();
    }
}