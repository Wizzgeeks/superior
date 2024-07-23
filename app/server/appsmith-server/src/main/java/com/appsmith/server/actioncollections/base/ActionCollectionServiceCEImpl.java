package com.appsmith.server.actioncollections.base;

import com.appsmith.external.helpers.AppsmithBeanUtils;
import com.appsmith.external.models.ActionDTO;
import com.appsmith.external.models.CreatorContextType;
import com.appsmith.external.models.DefaultResources;
import com.appsmith.external.models.Policy;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.acl.PolicyGenerator;
import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.defaultresources.DefaultResourcesService;
import com.appsmith.server.domains.ActionCollection;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.dtos.ActionCollectionDTO;
import com.appsmith.server.dtos.ActionCollectionViewDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.ResponseUtils;
import com.appsmith.server.newactions.base.NewActionService;
import com.appsmith.server.repositories.ActionCollectionRepository;
import com.appsmith.server.services.AnalyticsService;
import com.appsmith.server.services.BaseService;
import com.appsmith.server.solutions.ActionPermission;
import com.appsmith.server.solutions.ApplicationPermission;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.appsmith.external.helpers.AppsmithBeanUtils.copyNewFieldValuesIntoOldObject;
import static java.lang.Boolean.TRUE;

@Slf4j
public class ActionCollectionServiceCEImpl extends BaseService<ActionCollectionRepository, ActionCollection, String>
        implements ActionCollectionServiceCE {

    private final NewActionService newActionService;
    private final PolicyGenerator policyGenerator;
    private final ApplicationService applicationService;
    private final ResponseUtils responseUtils;
    private final ApplicationPermission applicationPermission;
    private final ActionPermission actionPermission;
    private final DefaultResourcesService<ActionCollection> defaultResourcesService;
    private final DefaultResourcesService<ActionCollectionDTO> dtoDefaultResourcesService;
    private final DefaultResourcesService<NewAction> newActionDefaultResourcesService;
    private final DefaultResourcesService<ActionDTO> actionDTODefaultResourcesService;

    @Autowired
    public ActionCollectionServiceCEImpl(
            Validator validator,
            ActionCollectionRepository repository,
            AnalyticsService analyticsService,
            NewActionService newActionService,
            PolicyGenerator policyGenerator,
            ApplicationService applicationService,
            ResponseUtils responseUtils,
            ApplicationPermission applicationPermission,
            ActionPermission actionPermission,
            DefaultResourcesService<ActionCollection> defaultResourcesService,
            DefaultResourcesService<ActionCollectionDTO> dtoDefaultResourcesService,
            DefaultResourcesService<NewAction> newActionDefaultResourcesService,
            DefaultResourcesService<ActionDTO> actionDTODefaultResourcesService) {

        super(validator, repository, analyticsService);
        this.newActionService = newActionService;
        this.policyGenerator = policyGenerator;
        this.applicationService = applicationService;
        this.responseUtils = responseUtils;
        this.applicationPermission = applicationPermission;
        this.actionPermission = actionPermission;
        this.defaultResourcesService = defaultResourcesService;
        this.dtoDefaultResourcesService = dtoDefaultResourcesService;
        this.newActionDefaultResourcesService = newActionDefaultResourcesService;
        this.actionDTODefaultResourcesService = actionDTODefaultResourcesService;
    }

    @Override
    public Flux<ActionCollection> findAllByApplicationIdAndViewMode(
            String applicationId, Boolean viewMode, AclPermission permission, Sort sort) {
        return repository
                .findByApplicationId(applicationId, permission, sort)
                // In case of view mode being true, filter out all the actions which haven't been published
                .flatMap(collection -> {
                    if (Boolean.TRUE.equals(viewMode)) {
                        // In case we are trying to fetch published actions but this action has not been published, do
                        // not return
                        if (collection.getPublishedCollection() == null) {
                            return Mono.empty();
                        }
                    }
                    // No need to handle the edge case of unpublished action not being present. This is not possible
                    // because every created action starts from an unpublishedAction state.

                    return Mono.just(collection);
                });
    }

    @Override
    public void generateAndSetPolicies(NewPage page, ActionCollection actionCollection) {
        Set<Policy> documentPolicies =
                policyGenerator.getAllChildPolicies(page.getPolicies(), NewPage.class, NewAction.class);
        actionCollection.setPolicies(documentPolicies);
    }

    @Override
    public Mono<ActionCollection> save(ActionCollection collection) {
        setGitSyncIdInActionCollection(collection);
        return repository.save(collection);
    }

    protected void setGitSyncIdInActionCollection(ActionCollection collection) {
        if (collection.getGitSyncId() == null) {
            collection.setGitSyncId(collection.getApplicationId() + "_" + UUID.randomUUID());
        }
    }

    @Override
    public Flux<ActionCollection> saveAll(List<ActionCollection> collections) {
        collections.forEach(collection -> {
            setGitSyncIdInActionCollection(collection);
        });
        return repository.saveAll(collections);
    }

    @Override
    public Mono<ActionCollection> findByIdAndBranchName(String id, String branchName) {
        // TODO sanitise response for default IDs
        return this.findByBranchNameAndDefaultCollectionId(branchName, id, actionPermission.getReadPermission());
    }

    @Override
    public Flux<ActionCollectionDTO> getPopulatedActionCollectionsByViewMode(
            MultiValueMap<String, String> params, Boolean viewMode) {
        return this.getActionCollectionsByViewMode(params, viewMode)
                .flatMap(actionCollectionDTO -> this.populateActionCollectionByViewMode(actionCollectionDTO, viewMode));
    }

    @Override
    public Flux<ActionCollectionDTO> getPopulatedActionCollectionsByViewMode(
            MultiValueMap<String, String> params, Boolean viewMode, String branchName) {
        MultiValueMap<String, String> updatedMap = new LinkedMultiValueMap<>(params);
        if (!StringUtils.isEmpty(branchName)) {
            updatedMap.add(FieldName.BRANCH_NAME, branchName);
        }
        return this.getPopulatedActionCollectionsByViewMode(updatedMap, viewMode)
                .map(responseUtils::updateCollectionDTOWithDefaultResources);
    }

    @Override
    public Mono<ActionCollectionDTO> populateActionCollectionByViewMode(
            ActionCollectionDTO actionCollectionDTO1, Boolean viewMode) {
        return newActionService
                .findByCollectionIdAndViewMode(
                        actionCollectionDTO1.getId(), viewMode, actionPermission.getReadPermission())
                .map(action -> newActionService.generateActionByViewMode(action, false))
                .collectList()
                .flatMap(actionsList -> splitValidActionsByViewMode(actionCollectionDTO1, actionsList, viewMode));
    }

    /**
     * This method splits the actions associated to an action collection into valid and archived actions
     */
    @Override
    public Mono<ActionCollectionDTO> splitValidActionsByViewMode(
            ActionCollectionDTO actionCollectionDTO, List<ActionDTO> actionsList, Boolean viewMode) {
        return Mono.just(actionCollectionDTO).map(actionCollectionDTO1 -> {
            final List<String> collect = actionsList.stream()
                    .parallel()
                    .map(ActionDTO::getPluginId)
                    .distinct()
                    .toList();
            if (collect.size() == 1) {
                actionCollectionDTO.setPluginId(collect.get(0));
                actionCollectionDTO.setPluginType(actionsList.get(0).getPluginType());
            }
            List<ActionDTO> validActionList = new ArrayList<>(actionsList);
            actionCollectionDTO.setActions(validActionList);
            return actionCollectionDTO;
        });
    }

    @Override
    public Flux<ActionCollectionViewDTO> getActionCollectionsForViewMode(String applicationId, String branchName) {
        if (applicationId == null || applicationId.isEmpty()) {
            return Flux.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.APPLICATION_ID));
        }

        return applicationService
                .findBranchedApplicationId(branchName, applicationId, applicationPermission.getReadPermission())
                .flatMapMany(branchedApplicationId -> repository
                        .findByApplicationIdAndViewMode(
                                branchedApplicationId, true, actionPermission.getExecutePermission())
                        .flatMap(this::generateActionCollectionViewDTO));
    }

    @Override
    public Mono<ActionCollectionViewDTO> generateActionCollectionViewDTO(ActionCollection actionCollection) {
        return generateActionCollectionViewDTO(actionCollection, actionPermission.getExecutePermission(), true);
    }

    protected Mono<ActionCollectionViewDTO> generateActionCollectionViewDTO(
            ActionCollection actionCollection, AclPermission aclPermission, boolean viewMode) {
        ActionCollectionDTO actionCollectionDTO = null;
        if (viewMode) {
            actionCollectionDTO = actionCollection.getPublishedCollection();
        } else {
            actionCollectionDTO = actionCollection.getUnpublishedCollection();
        }
        if (Objects.isNull(actionCollectionDTO)) {
            return Mono.empty();
        }
        ActionCollectionViewDTO actionCollectionViewDTO = new ActionCollectionViewDTO();
        actionCollectionViewDTO.setId(actionCollection.getId());
        actionCollectionViewDTO.setName(actionCollectionDTO.getName());
        actionCollectionViewDTO.setPageId(actionCollectionDTO.getPageId());
        actionCollectionViewDTO.setApplicationId(actionCollection.getApplicationId());
        actionCollectionViewDTO.setVariables(actionCollectionDTO.getVariables());
        actionCollectionViewDTO.setBody(actionCollectionDTO.getBody());
        // Update default resources :
        // actionCollection.defaultResources contains appId, collectionId and branch(optional).
        // Default pageId will be taken from publishedCollection.defaultResources
        DefaultResources defaults = actionCollection.getDefaultResources();
        // Consider a situation when collection is not published but user is viewing in deployed
        // mode
        if (actionCollectionDTO.getDefaultResources() != null && defaults != null) {
            defaults.setPageId(actionCollectionDTO.getDefaultResources().getPageId());
        } else {
            log.debug(
                    "Unreachable state, unable to find default ids for actionCollection: {}", actionCollection.getId());
            if (defaults == null) {
                defaults = new DefaultResources();
                defaults.setApplicationId(actionCollection.getApplicationId());
                defaults.setCollectionId(actionCollection.getId());
            }
            defaults.setPageId(actionCollection.getPublishedCollection().getPageId());
        }
        actionCollectionViewDTO.setDefaultResources(defaults);
        return newActionService
                .findByCollectionIdAndViewMode(actionCollection.getId(), viewMode, aclPermission)
                .map(action -> newActionService.generateActionByViewMode(action, viewMode))
                .collectList()
                .map(actionDTOList -> {
                    actionCollectionViewDTO.setActions(actionDTOList);
                    return actionCollectionViewDTO;
                })
                .map(responseUtils::updateActionCollectionViewDTOWithDefaultResources);
    }

    @Override
    public Flux<ActionCollectionDTO> getActionCollectionsByViewMode(
            MultiValueMap<String, String> params, Boolean viewMode) {
        if (params == null || viewMode == null) {
            return Flux.empty();
        }
        return getActionCollectionsFromRepoByViewMode(params, viewMode)
                .flatMap(actionCollection -> generateActionCollectionByViewMode(actionCollection, viewMode));
    }

    protected Flux<ActionCollection> getActionCollectionsFromRepoByViewMode(
            MultiValueMap<String, String> params, Boolean viewMode) {
        if (params.getFirst(FieldName.APPLICATION_ID) != null) {
            // Fetch unpublished pages because GET actions is only called during edit mode. For view mode, different
            // function call is made which takes care of returning only the essential fields of an action
            return applicationService
                    .findBranchedApplicationId(
                            params.getFirst(FieldName.BRANCH_NAME),
                            params.getFirst(FieldName.APPLICATION_ID),
                            applicationPermission.getReadPermission())
                    .flatMapMany(childApplicationId -> repository.findByApplicationIdAndViewMode(
                            childApplicationId, viewMode, actionPermission.getReadPermission()));
        }

        String name = null;
        List<String> pageIds = new ArrayList<>();
        String branch = null;

        // In the edit mode, the actions should be displayed in the order they were created.
        Sort sort = Sort.by(FieldName.CREATED_AT);

        if (params.getFirst(FieldName.NAME) != null) {
            name = params.getFirst(FieldName.NAME);
        }

        if (params.getFirst(FieldName.BRANCH_NAME) != null) {
            branch = params.getFirst(FieldName.BRANCH_NAME);
        }

        if (params.getFirst(FieldName.PAGE_ID) != null) {
            pageIds.add(params.getFirst(FieldName.PAGE_ID));
        }
        return repository.findAllActionCollectionsByNameDefaultPageIdsViewModeAndBranch(
                name, pageIds, viewMode, branch, actionPermission.getReadPermission(), sort);
    }

    @Override
    public Mono<ActionCollectionDTO> update(String id, ActionCollectionDTO actionCollectionDTO) {
        if (id == null) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.ID));
        }

        Mono<ActionCollection> actionCollectionMono = repository
                .findById(id, actionPermission.getEditPermission())
                .switchIfEmpty(Mono.error(
                        new AppsmithException(AppsmithError.ACL_NO_RESOURCE_FOUND, FieldName.ACTION_COLLECTION, id)))
                .cache();

        return actionCollectionMono
                .map(dbActionCollection -> {
                    copyNewFieldValuesIntoOldObject(actionCollectionDTO, dbActionCollection.getUnpublishedCollection());
                    // No need to save defaultPageId at actionCollection level as this will be stored inside the
                    // actionCollectionDTO
                    defaultResourcesService.initialize(
                            dbActionCollection,
                            dbActionCollection.getDefaultResources().getBranchName(),
                            false);
                    return dbActionCollection;
                })
                .flatMap(actionCollection -> this.update(id, actionCollection))
                .flatMap(repository::setUserPermissionsInObject)
                .flatMap(actionCollection -> this.generateActionCollectionByViewMode(actionCollection, false)
                        .flatMap(actionCollectionDTO1 -> this.populateActionCollectionByViewMode(
                                actionCollection.getUnpublishedCollection(), false)));
    }

    @Override
    public Mono<ActionCollectionDTO> deleteWithoutPermissionUnpublishedActionCollection(String id) {
        return deleteUnpublishedActionCollection(id, null, actionPermission.getDeletePermission());
    }

    @Override
    public Mono<ActionCollectionDTO> deleteUnpublishedActionCollection(String id) {
        return deleteUnpublishedActionCollection(
                id, actionPermission.getDeletePermission(), actionPermission.getDeletePermission());
    }

    @Override
    public Mono<ActionCollectionDTO> deleteUnpublishedActionCollection(
            String id, AclPermission permission, AclPermission deleteActionPermission) {
        Mono<ActionCollection> actionCollectionMono = repository
                .findById(id, permission)
                .switchIfEmpty(Mono.error(
                        new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.ACTION_COLLECTION, id)));
        return actionCollectionMono
                .flatMap(toDelete -> {
                    Mono<ActionCollection> modifiedActionCollectionMono;

                    if (toDelete.getPublishedCollection() != null
                            && toDelete.getPublishedCollection().getName() != null) {
                        toDelete.getUnpublishedCollection().setDeletedAt(Instant.now());
                        modifiedActionCollectionMono = newActionService
                                .findByCollectionIdAndViewMode(id, false, deleteActionPermission)
                                .flatMap(newAction -> newActionService
                                        .deleteGivenNewAction(newAction)
                                        // return an empty action so that the filter can remove it from the list
                                        .onErrorResume(throwable -> {
                                            log.debug(
                                                    "Failed to delete action with id {} for collection: {}",
                                                    newAction.getId(),
                                                    toDelete.getUnpublishedCollection()
                                                            .getName());
                                            log.error(throwable.getMessage());
                                            return Mono.empty();
                                        }))
                                .collectList()
                                .then(repository.save(toDelete))
                                .flatMap(modifiedActionCollection -> {
                                    return analyticsService.sendArchiveEvent(
                                            modifiedActionCollection, getAnalyticsProperties(modifiedActionCollection));
                                });
                    } else {
                        // This actionCollection was never published. This document can be safely archived
                        modifiedActionCollectionMono = this.archiveById(toDelete.getId());
                    }

                    return modifiedActionCollectionMono;
                })
                .flatMap(updatedAction -> generateActionCollectionByViewMode(updatedAction, false));
    }

    @Override
    public Mono<ActionCollectionDTO> deleteUnpublishedActionCollection(String id, String branchName) {
        Mono<String> branchedCollectionId = StringUtils.isEmpty(branchName)
                ? Mono.just(id)
                : this.findByBranchNameAndDefaultCollectionId(branchName, id, actionPermission.getDeletePermission())
                        .map(ActionCollection::getId);

        return branchedCollectionId
                .flatMap(this::deleteUnpublishedActionCollection)
                .map(responseUtils::updateCollectionDTOWithDefaultResources)
                .flatMap(actionCollectionDTO ->
                        saveLastEditInformationInParent(actionCollectionDTO).thenReturn(actionCollectionDTO));
    }

    @Override
    public Mono<ActionCollectionDTO> generateActionCollectionByViewMode(
            ActionCollection actionCollection, Boolean viewMode) {
        ActionCollectionDTO actionCollectionDTO = null;

        if (TRUE.equals(viewMode)) {
            if (actionCollection.getPublishedCollection() != null) {
                actionCollectionDTO = actionCollection.getPublishedCollection();
            } else {
                // We are trying to fetch published action but it doesnt exist because the action hasn't been published
                // yet
                return Mono.empty();
            }
        } else {
            if (actionCollection.getUnpublishedCollection() != null) {
                actionCollectionDTO = actionCollection.getUnpublishedCollection();
            }
        }

        assert actionCollectionDTO != null;
        actionCollectionDTO.populateTransientFields(actionCollection);

        return Mono.just(actionCollectionDTO);
    }

    @Override
    public Mono<ActionCollection> findById(String id, AclPermission aclPermission) {
        return repository.findById(id, aclPermission);
    }

    @Override
    public Mono<ActionCollectionDTO> findActionCollectionDTObyIdAndViewMode(
            String id, Boolean viewMode, AclPermission permission) {
        return this.findById(id, permission)
                .flatMap(action -> this.generateActionCollectionByViewMode(action, viewMode));
    }

    @Override
    public Mono<List<ActionCollection>> archiveActionCollectionByApplicationId(
            String applicationId, AclPermission permission) {
        return repository
                .findByApplicationId(applicationId, permission, null)
                .flatMap(this::archiveGivenActionCollection)
                .collectList();
    }

    @Override
    public Flux<ActionCollection> findByPageId(String pageId) {
        return repository.findByPageId(pageId);
    }

    @Override
    public Flux<ActionCollectionDTO> getCollectionsByPageIdAndViewMode(
            String pageId, boolean viewMode, AclPermission permission) {
        return repository
                .findByPageIdAndViewMode(pageId, viewMode, permission)
                .flatMap(actionCollection -> generateActionCollectionByViewMode(actionCollection, viewMode));
    }

    @Override
    public Flux<ActionCollection> findByPageIdsForExport(List<String> pageIds, AclPermission permission) {
        return repository.findByPageIds(pageIds, permission).doOnNext(actionCollection -> {
            actionCollection.getUnpublishedCollection().populateTransientFields(actionCollection);
            if (actionCollection.getPublishedCollection() != null
                    && StringUtils.hasText(
                            actionCollection.getPublishedCollection().getName())) {
                actionCollection.getPublishedCollection().populateTransientFields(actionCollection);
            }
        });
    }

    @Override
    public Mono<ActionCollection> archiveById(String id) {
        Mono<ActionCollection> actionCollectionMono = repository
                .findById(id)
                .switchIfEmpty(Mono.error(
                        new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.ACTION_COLLECTION, id)))
                .cache();
        return actionCollectionMono.flatMap(this::archiveGivenActionCollection);
    }

    protected Mono<ActionCollection> archiveGivenActionCollection(ActionCollection actionCollection) {
        Flux<NewAction> unpublishedJsActionsFlux = newActionService.findByCollectionIdAndViewMode(
                actionCollection.getId(), false, actionPermission.getDeletePermission());
        Flux<NewAction> publishedJsActionsFlux = newActionService.findByCollectionIdAndViewMode(
                actionCollection.getId(), true, actionPermission.getDeletePermission());
        return unpublishedJsActionsFlux
                .mergeWith(publishedJsActionsFlux)
                .flatMap(toArchive -> newActionService
                        .archiveGivenNewAction(toArchive)
                        // return an empty action so that the filter can remove it from the list
                        .onErrorResume(throwable -> {
                            log.debug(
                                    "Failed to delete action with id {} for collection with id: {}",
                                    toArchive.getId(),
                                    actionCollection.getId());
                            log.error(throwable.getMessage());
                            return Mono.empty();
                        }))
                .collectList()
                .then(repository.archive(actionCollection).thenReturn(actionCollection))
                .flatMap(deletedActionCollection -> analyticsService.sendDeleteEvent(
                        deletedActionCollection, getAnalyticsProperties(deletedActionCollection)));
    }

    @Override
    public Mono<ActionCollection> findByBranchNameAndDefaultCollectionId(
            String branchName, String defaultCollectionId, AclPermission permission) {

        if (StringUtils.isEmpty(defaultCollectionId)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.COLLECTION_ID));
        } else if (StringUtils.isEmpty(branchName)) {
            return this.findById(defaultCollectionId, permission)
                    .switchIfEmpty(Mono.error(new AppsmithException(
                            AppsmithError.NO_RESOURCE_FOUND, FieldName.ACTION_COLLECTION, defaultCollectionId)));
        }
        return repository
                .findByBranchNameAndDefaultCollectionId(branchName, defaultCollectionId, permission)
                .switchIfEmpty(Mono.error(new AppsmithException(
                        AppsmithError.ACL_NO_RESOURCE_FOUND, FieldName.ACTION_COLLECTION, defaultCollectionId)));
    }

    @Override
    public Map<String, Object> getAnalyticsProperties(ActionCollection savedActionCollection) {
        final ActionCollectionDTO unpublishedCollection = savedActionCollection.getUnpublishedCollection();
        Map<String, Object> analyticsProperties = new HashMap<>();
        analyticsProperties.put("actionCollectionName", ObjectUtils.defaultIfNull(unpublishedCollection.getName(), ""));
        analyticsProperties.put(
                "applicationId", ObjectUtils.defaultIfNull(savedActionCollection.getApplicationId(), ""));
        analyticsProperties.put("pageId", ObjectUtils.defaultIfNull(unpublishedCollection.getPageId(), ""));
        analyticsProperties.put("orgId", ObjectUtils.defaultIfNull(savedActionCollection.getWorkspaceId(), ""));
        return analyticsProperties;
    }

    @Override
    public Mono<ActionCollection> create(ActionCollection collection) {
        setGitSyncIdInActionCollection(collection);
        return super.create(collection);
    }

    @Override
    public void populateDefaultResources(
            ActionCollection actionCollection, ActionCollection branchedActionCollection, String branchName) {
        DefaultResources defaultResources = branchedActionCollection.getDefaultResources();
        // Create new action but keep defaultApplicationId and defaultActionId same for both the actions
        defaultResources.setBranchName(branchName);
        actionCollection.setDefaultResources(defaultResources);

        String defaultPageId = branchedActionCollection.getUnpublishedCollection() != null
                ? branchedActionCollection
                        .getUnpublishedCollection()
                        .getDefaultResources()
                        .getPageId()
                : branchedActionCollection
                        .getPublishedCollection()
                        .getDefaultResources()
                        .getPageId();
        DefaultResources defaultsDTO = new DefaultResources();
        defaultsDTO.setPageId(defaultPageId);
        if (actionCollection.getUnpublishedCollection() != null) {
            actionCollection.getUnpublishedCollection().setDefaultResources(defaultsDTO);
        }
        if (actionCollection.getPublishedCollection() != null) {
            actionCollection.getPublishedCollection().setDefaultResources(defaultsDTO);
        }
        actionCollection
                .getUnpublishedCollection()
                .setDeletedAt(
                        branchedActionCollection.getUnpublishedCollection().getDeletedAt());
        actionCollection.setDeletedAt(branchedActionCollection.getDeletedAt());
        // Set policies from existing branch object
        actionCollection.setPolicies(branchedActionCollection.getPolicies());
    }

    @Override
    public Flux<ActionCollection> findAllActionCollectionsByContextIdAndContextTypeAndViewMode(
            String contextId, CreatorContextType contextType, AclPermission permission, boolean viewMode) {
        if (viewMode) {
            return repository.findAllPublishedActionCollectionsByContextIdAndContextType(
                    contextId, contextType, permission);
        }
        return repository.findAllUnpublishedActionCollectionsByContextIdAndContextType(
                contextId, contextType, permission);
    }

    protected Mono<ActionDTO> createJsAction(ActionCollection actionCollection, ActionDTO action) {
        ActionCollectionDTO collectionDTO = actionCollection.getUnpublishedCollection();

        /**
         * If the Datasource is null, create one and set the autoGenerated flag to true. This is required because spring-data
         * cannot add the createdAt and updatedAt properties for null embedded objects. At this juncture, we couldn't find
         * a way to disable the auditing for nested objects.
         *
         */
        if (action.getDatasource() == null) {
            action.autoGenerateDatasource();
        }
        action.getDatasource().setWorkspaceId(collectionDTO.getWorkspaceId());
        action.getDatasource().setPluginId(collectionDTO.getPluginId());
        action.getDatasource().setName(FieldName.UNUSED_DATASOURCE);

        // Make sure that the proper values are used for the new action
        // Scope the actions' fully qualified names by collection name
        action.setFullyQualifiedName(collectionDTO.getName() + "." + action.getName());
        action.setPageId(collectionDTO.getPageId());
        action.setPluginType(collectionDTO.getPluginType());
        action.setDefaultResources(collectionDTO.getDefaultResources());
        action.getDefaultResources()
                .setCollectionId(actionCollection.getDefaultResources().getCollectionId());
        action.setApplicationId(actionCollection.getApplicationId());

        // Action doesn't exist. Create now.
        NewAction newAction = newActionService.generateActionDomain(action);
        newAction.setUnpublishedAction(action);

        Set<Policy> actionCollectionPolicies = new HashSet<>();
        actionCollection.getPolicies().forEach(policy -> {
            Policy actionPolicy = Policy.builder()
                    .permission(policy.getPermission())
                    .permissionGroups(policy.getPermissionGroups())
                    .build();

            actionCollectionPolicies.add(actionPolicy);
        });

        newAction.setPolicies(actionCollectionPolicies);
        newActionService.setCommonFieldsFromActionDTOIntoNewAction(action, newAction);
        newAction.setDefaultResources(actionCollection.getDefaultResources());
        newActionDefaultResourcesService.initialize(
                newAction, newAction.getDefaultResources().getBranchName(), false);
        actionDTODefaultResourcesService.initialize(
                action, newAction.getDefaultResources().getBranchName(), false);

        Mono<NewAction> sendAnalyticsMono =
                analyticsService.sendCreateEvent(newAction, newActionService.getAnalyticsProperties(newAction));

        return newActionService
                .validateAndSaveActionToRepository(newAction)
                .flatMap(savedAction -> sendAnalyticsMono.thenReturn(savedAction));
    }

    @Override
    public Mono<ActionCollectionDTO> validateAndSaveCollection(ActionCollection actionCollection) {
        ActionCollectionDTO collectionDTO = actionCollection.getUnpublishedCollection();

        return validateActionCollection(actionCollection)
                .thenReturn(collectionDTO.getActions())
                .defaultIfEmpty(List.of())
                .flatMapMany(Flux::fromIterable)
                .flatMap(action -> {
                    if (action.getId() == null) {
                        return createJsAction(actionCollection, action);
                    }
                    // This would occur when the new collection is created by grouping existing actions
                    // This could be a future enhancement for js editor templates,
                    // but is also useful for generic collections
                    // We do not expect to have to update the action at this point
                    return Mono.just(action);
                })
                .collectList()
                .flatMap(actions -> {
                    populateDefaultResources(actionCollection, collectionDTO, actions);

                    // Create collection and return with actions
                    final Mono<ActionCollection> actionCollectionMono = this.create(actionCollection)
                            .flatMap(savedActionCollection -> {
                                // If the default collection is not set then current collection will be the default one
                                if (StringUtils.isEmpty(savedActionCollection
                                        .getDefaultResources()
                                        .getCollectionId())) {
                                    savedActionCollection
                                            .getDefaultResources()
                                            .setCollectionId(savedActionCollection.getId());
                                    return this.save(savedActionCollection);
                                }
                                return Mono.just(savedActionCollection);
                            })
                            .flatMap(repository::setUserPermissionsInObject)
                            .cache();

                    return actionCollectionMono
                            .map(actionCollection1 -> {
                                actions.forEach(actionDTO -> {
                                    // Update all the actions in the list to belong to this collection
                                    actionDTO.setCollectionId(actionCollection1.getId());
                                    if (StringUtils.isEmpty(
                                            actionDTO.getDefaultResources().getCollectionId())) {
                                        actionDTO.getDefaultResources().setCollectionId(actionCollection1.getId());
                                    }
                                });
                                return actions;
                            })
                            .flatMapMany(Flux::fromIterable)
                            .collectList()
                            .zipWith(actionCollectionMono)
                            .flatMap(tuple1 -> {
                                final List<ActionDTO> actionDTOList = tuple1.getT1();
                                final ActionCollection actionCollection1 = tuple1.getT2();
                                return generateActionCollectionByViewMode(actionCollection, false)
                                        .flatMap(actionCollectionDTO -> splitValidActionsByViewMode(
                                                actionCollection1.getUnpublishedCollection(), actionDTOList, false));
                            });
                });
    }

    private Mono<ActionCollection> validateActionCollection(ActionCollection actionCollection) {
        ActionCollectionDTO collectionDTO = actionCollection.getUnpublishedCollection();

        collectionDTO.populateTransientFields(actionCollection);

        final Set<String> validationMessages = collectionDTO.validate();
        if (!validationMessages.isEmpty()) {
            return Mono.error(new AppsmithException(
                    AppsmithError.INVALID_ACTION_COLLECTION, collectionDTO.getName(), validationMessages.toString()));
        }

        String branchName = null;

        if (actionCollection.getDefaultResources() != null) {
            branchName = actionCollection.getDefaultResources().getBranchName();
        }

        defaultResourcesService.initialize(actionCollection, branchName, false);
        dtoDefaultResourcesService.initialize(collectionDTO, branchName, false);

        return Mono.just(actionCollection);
    }

    @Override
    public Mono<Void> bulkValidateAndInsertActionCollectionInRepository(List<ActionCollection> actionCollectionList) {
        return Flux.fromIterable(actionCollectionList)
                .flatMap(this::validateActionCollection)
                .collectList()
                .flatMap(repository::bulkInsert);
    }

    @Override
    public Mono<Void> bulkValidateAndUpdateActionCollectionInRepository(List<ActionCollection> actionCollectionList) {
        return Flux.fromIterable(actionCollectionList)
                .flatMap(this::validateActionCollection)
                .collectList()
                .flatMap(repository::bulkUpdate);
    }

    protected void populateDefaultResources(
            ActionCollection actionCollection, ActionCollectionDTO collectionDTO, List<ActionDTO> actions) {
        // Store the default resource ids
        // Only store defaultPageId for collectionDTO level resource
        DefaultResources defaultDTOResource = new DefaultResources();
        AppsmithBeanUtils.copyNewFieldValuesIntoOldObject(collectionDTO.getDefaultResources(), defaultDTOResource);

        defaultDTOResource.setApplicationId(null);
        defaultDTOResource.setCollectionId(null);
        defaultDTOResource.setBranchName(null);
        if (StringUtils.isEmpty(defaultDTOResource.getPageId())) {
            defaultDTOResource.setPageId(collectionDTO.getPageId());
        }
        collectionDTO.setDefaultResources(defaultDTOResource);

        // Only store branchName, defaultApplicationId and defaultActionCollectionId for ActionCollection
        // level resource
        DefaultResources defaults = new DefaultResources();
        AppsmithBeanUtils.copyNewFieldValuesIntoOldObject(actionCollection.getDefaultResources(), defaults);
        defaults.setPageId(null);
        if (StringUtils.isEmpty(defaults.getApplicationId())) {
            defaults.setApplicationId(actionCollection.getApplicationId());
        }
        actionCollection.setDefaultResources(defaults);
    }

    @Override
    public Mono<Void> saveLastEditInformationInParent(ActionCollectionDTO actionCollectionDTO) {
        // Do nothing as this is already taken care for JS objects in the context of page
        return Mono.empty().then();
    }
}