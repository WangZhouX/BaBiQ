package com.wzx.babiq.server.workunit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.agent.flow.BabiqFlowNode;
import com.wzx.babiq.server.agent.flow.BabiqFlowStructure;
import com.wzx.babiq.server.agent.flow.BabiqFlowTopology;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * WorkUnit flow config validator shared by JSON-RPC and Agent tool updates.
 */
public final class WorkUnitFlowConfigValidator {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();
    private static final Pattern ASCII_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    private WorkUnitFlowConfigValidator() {
    }

    public static void validateOrThrow(String configJson, String structureJson) {
        FlowConfig config = readConfig(configJson);
        List<FlowNodeConfig> nodeConfigs = config.nodes() == null ? List.of() : config.nodes();
        if (nodeConfigs.isEmpty()) {
            if (structureJson != null && !structureJson.isBlank()) {
                throw new IllegalArgumentException("flow structure cannot be set without nodes");
            }
            return;
        }
        List<BabiqFlowNode> nodes = IntStream.range(0, nodeConfigs.size())
                .mapToObj(index -> toFlowNode(nodeConfigs.get(index), index + 1))
                .toList();
        BabiqFlowStructure structure = readStructure(structureJson, parseTopology(config.topology()), nodes);
        try {
            structure.validateAgainst(nodes);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid flow structure: " + exception.getMessage(), exception);
        }
    }

    public static String validationSummary(String configJson, String structureJson) {
        if (configJson == null || configJson.isBlank()) {
            return "validation=empty";
        }
        try {
            validateOrThrow(configJson, structureJson);
            return "validation=ok";
        } catch (IllegalArgumentException exception) {
            return "validation=error: " + exception.getMessage();
        }
    }

    public static List<String> emptyTaskNodeIds(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return List.of();
        }
        FlowConfig config = readConfig(configJson);
        List<FlowNodeConfig> nodes = config.nodes() == null ? List.of() : config.nodes();
        return nodes.stream()
                .filter(node -> node != null && (node.task() == null || node.task().isBlank()))
                .map(FlowNodeConfig::id)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    private static FlowConfig readConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            throw new IllegalArgumentException("flow configJson cannot be blank");
        }
        try {
            return JSON.readValue(configJson, FlowConfig.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid flow configJson: " + exception.getOriginalMessage(), exception);
        }
    }

    private static BabiqFlowStructure readStructure(String structureJson,
                                                    BabiqFlowTopology topology,
                                                    List<BabiqFlowNode> nodes) {
        if (structureJson == null || structureJson.isBlank()) {
            return BabiqFlowStructure.fromLegacy(topology, nodes);
        }
        try {
            return JSON.readValue(structureJson, BabiqFlowStructure.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid flow structureJson: " + exception.getOriginalMessage(), exception);
        }
    }

    private static BabiqFlowNode toFlowNode(FlowNodeConfig config, int order) {
        String id = config == null ? null : config.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("flow node id cannot be blank");
        }
        String name = asciiName(config.name(), id, order);
        return new BabiqFlowNode(
                id.trim(),
                name,
                textOrDefault(config.name(), id.trim()),
                textOrDefault(config.role(), name),
                config.task() == null ? "" : config.task(),
                List.of(),
                modelPolicy(config.model()),
                parseMode(config.mode()),
                order,
                null,
                name + "_output",
                List.of());
    }

    private static BabiqFlowTopology parseTopology(String topology) {
        if (topology == null || topology.isBlank()) {
            return BabiqFlowTopology.SEQUENTIAL;
        }
        return switch (topology.trim().toUpperCase(Locale.ROOT)) {
            case "PARALLEL" -> BabiqFlowTopology.PARALLEL;
            case "ROUTING", "ROUTE" -> BabiqFlowTopology.ROUTING;
            default -> BabiqFlowTopology.SEQUENTIAL;
        };
    }

    private static BabiqAgentMode parseMode(String mode) {
        if ("WORKSPACE_TOOL".equalsIgnoreCase(mode)) {
            return BabiqAgentMode.WORKSPACE_TOOL;
        }
        return BabiqAgentMode.READ_ONLY_TOOL;
    }

    private static BabiqAgentSpec.ModelPolicy modelPolicy(String model) {
        if (model == null || model.isBlank() || "inherit".equalsIgnoreCase(model)) {
            return BabiqAgentSpec.ModelPolicy.inherit();
        }
        if (model.startsWith("provider:")) {
            return BabiqAgentSpec.ModelPolicy.provider(model.substring("provider:".length()));
        }
        return BabiqAgentSpec.ModelPolicy.provider(model);
    }

    private static String asciiName(String preferred, String id, int order) {
        String candidate = preferred != null && ASCII_NAME.matcher(preferred.trim()).matches()
                ? preferred.trim()
                : id == null ? "" : id.trim();
        if (ASCII_NAME.matcher(candidate).matches()) {
            return candidate;
        }
        return "node_" + order;
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FlowConfig(
            String topology,
            List<FlowNodeConfig> nodes
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FlowNodeConfig(
            String id,
            String name,
            String role,
            String task,
            String model,
            String mode
    ) {
    }
}
