package com.appsmith.server.controllers.ce;

import com.appsmith.external.models.TriggerRequestDTO;
import com.appsmith.external.models.TriggerResultDTO;
import com.appsmith.external.views.Views;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.constants.Url;
import com.appsmith.server.domains.Plugin;
import com.appsmith.server.dtos.ResponseDTO;
import com.appsmith.server.plugins.base.PluginService;
import com.appsmith.server.plugins.solutions.PluginTriggerSolution;
import com.fasterxml.jackson.annotation.JsonView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RequestMapping(Url.PLUGIN_URL)
@RequiredArgsConstructor
@Slf4j
public class PluginControllerCE {

    protected final PluginService service;

    private final PluginTriggerSolution pluginTriggerSolution;

    @JsonView(Views.Public.class)
    @GetMapping
    public Mono<ResponseDTO<List<Plugin>>> getAll(@RequestParam String workspaceId) {
        log.debug("Getting all plugins in workspace {}", workspaceId);
        return service.getInWorkspace(workspaceId)
                .collectList()
                .map(resources -> new ResponseDTO<>(HttpStatus.OK.value(), resources, null));
    }

    @JsonView(Views.Public.class)
    @GetMapping("/{pluginId}/form")
    public Mono<ResponseDTO<Object>> getDatasourceForm(@PathVariable String pluginId) {
        return service.getFormConfig(pluginId).map(form -> new ResponseDTO<>(HttpStatus.OK.value(), form, null));
    }

    @JsonView(Views.Public.class)
    @GetMapping("/default/icons")
    public Mono<ResponseDTO<List<Plugin>>> getDefaultPluginIcons() {
        return service.getDefaultPluginIcons()
                .collectList()
                .map(data -> new ResponseDTO<>(HttpStatus.OK.value(), data, null));
    }

    @JsonView(Views.Public.class)
    @PostMapping("/{pluginId}/trigger")
    public Mono<ResponseDTO<TriggerResultDTO>> trigger(
            @PathVariable String pluginId,
            @RequestBody TriggerRequestDTO triggerRequestDTO,
            @RequestHeader(name = FieldName.HEADER_ENVIRONMENT_ID, required = false) String environmentId,
            ServerWebExchange serverWebExchange) {
        log.debug("Trigger received for Plugin {}", pluginId);
        return pluginTriggerSolution
                .trigger(
                        pluginId,
                        environmentId,
                        triggerRequestDTO,
                        serverWebExchange.getRequest().getHeaders())
                .map(triggerResultDTO -> new ResponseDTO<>(HttpStatus.OK.value(), triggerResultDTO, null));
    }

    @JsonView(Views.Public.class)
    @PostMapping(value = "/{pluginId}/trigger", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseDTO<TriggerResultDTO>> triggerMultipart(
            @PathVariable String pluginId,
            @RequestPart("files") Flux<FilePart> filePartFlux,
            @RequestPart("requestType") String requestType,
            @RequestHeader(name = FieldName.HEADER_ENVIRONMENT_ID, required = false) String environmentId,
            @RequestPart(name = FieldName.WORKSPACE_ID, required = false) String workspaceId,
            ServerWebExchange serverWebExchange) {
        log.debug("Trigger received for plugin {}", pluginId);
        return pluginTriggerSolution
                .trigger(
                        pluginId,
                        environmentId,
                        workspaceId,
                        filePartFlux,
                        requestType,
                        serverWebExchange.getRequest().getHeaders())
                .map(triggerResultDTO -> new ResponseDTO<>(HttpStatus.OK.value(), triggerResultDTO, null));
    }
}
