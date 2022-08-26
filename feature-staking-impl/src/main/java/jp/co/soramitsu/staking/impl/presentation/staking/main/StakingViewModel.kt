package jp.co.soramitsu.staking.impl.presentation.staking.main

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named
import jp.co.soramitsu.account.api.domain.interfaces.AccountInteractor
import jp.co.soramitsu.account.api.domain.model.accountId
import jp.co.soramitsu.common.address.AddressModel
import jp.co.soramitsu.common.base.BaseViewModel
import jp.co.soramitsu.common.compose.component.AssetSelectorState
import jp.co.soramitsu.common.compose.component.TitleValueViewState
import jp.co.soramitsu.common.mixin.api.Validatable
import jp.co.soramitsu.common.presentation.LoadingState
import jp.co.soramitsu.common.presentation.StoryGroupModel
import jp.co.soramitsu.common.resources.ResourceManager
import jp.co.soramitsu.common.utils.childScope
import jp.co.soramitsu.common.utils.withLoading
import jp.co.soramitsu.common.validation.ValidationExecutor
import jp.co.soramitsu.core.updater.UpdateSystem
import jp.co.soramitsu.runtime.multiNetwork.chain.model.kusamaChainId
import jp.co.soramitsu.staking.api.data.StakingAssetSelection
import jp.co.soramitsu.staking.api.data.StakingSharedState
import jp.co.soramitsu.staking.api.data.StakingType
import jp.co.soramitsu.staking.api.domain.model.StakingState
import jp.co.soramitsu.staking.impl.domain.StakingInteractor
import jp.co.soramitsu.staking.impl.domain.alerts.AlertsInteractor
import jp.co.soramitsu.staking.impl.domain.rewards.RewardCalculatorFactory
import jp.co.soramitsu.staking.impl.domain.validations.balance.ManageStakingValidationPayload
import jp.co.soramitsu.staking.impl.domain.validations.balance.ManageStakingValidationSystem
import jp.co.soramitsu.staking.impl.presentation.StakingRouter
import jp.co.soramitsu.staking.impl.presentation.common.SetupStakingProcess
import jp.co.soramitsu.staking.impl.presentation.common.SetupStakingSharedState
import jp.co.soramitsu.staking.impl.presentation.common.StakingAssetSelector
import jp.co.soramitsu.staking.impl.presentation.staking.balance.manageStakingActionValidationFailure
import jp.co.soramitsu.staking.impl.presentation.staking.bond.select.SelectBondMorePayload
import jp.co.soramitsu.staking.impl.presentation.staking.main.compose.EstimatedEarningsViewState
import jp.co.soramitsu.staking.impl.presentation.staking.main.compose.StakingAssetInfoViewState
import jp.co.soramitsu.staking.impl.presentation.staking.main.compose.default
import jp.co.soramitsu.staking.impl.presentation.staking.main.compose.update
import jp.co.soramitsu.staking.impl.presentation.staking.main.di.StakingViewStateFactory
import jp.co.soramitsu.staking.impl.presentation.staking.main.scenarios.BaseStakingViewModel
import jp.co.soramitsu.staking.impl.presentation.staking.main.scenarios.StakingScenario
import jp.co.soramitsu.staking.impl.presentation.staking.redeem.RedeemPayload
import jp.co.soramitsu.staking.impl.scenarios.StakingPoolInteractor
import jp.co.soramitsu.staking.impl.scenarios.parachain.StakingParachainScenarioInteractor
import jp.co.soramitsu.staking.impl.scenarios.relaychain.StakingRelayChainScenarioInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val CURRENT_ICON_SIZE = 40

@HiltViewModel
class StakingViewModel @Inject constructor(
    private val interactor: StakingInteractor,
    alertsInteractor: AlertsInteractor,
    stakingViewStateFactory: StakingViewStateFactory,
    private val router: StakingRouter,
    private val resourceManager: ResourceManager,
    private val validationExecutor: ValidationExecutor,
    @Named("StakingChainUpdateSystem") stakingUpdateSystem: UpdateSystem,
    stakingSharedState: StakingSharedState,
    parachainScenarioInteractor: StakingParachainScenarioInteractor,
    relayChainScenarioInteractor: StakingRelayChainScenarioInteractor,
    rewardCalculatorFactory: RewardCalculatorFactory,
    private val setupStakingSharedState: SetupStakingSharedState,
    private val stakingPoolInteractor: StakingPoolInteractor,
    private val accountInteractor: AccountInteractor
) : BaseViewModel(),
    BaseStakingViewModel,
    Validatable by validationExecutor {

    override val stakingStateScope: CoroutineScope
        get() = viewModelScope.childScope(supervised = true)

    private val stakingScenario = StakingScenario(
        stakingSharedState,
        this,
        interactor,
        parachainScenarioInteractor,
        relayChainScenarioInteractor,
        rewardCalculatorFactory,
        resourceManager,
        alertsInteractor,
        stakingViewStateFactory,
        stakingPoolInteractor
    )

    val assetSelectorMixin = StakingAssetSelector(stakingSharedState, this)

    val stakingTypeFlow = stakingSharedState.selectionItem.map { it.type }

    private val scenarioViewModelFlow = stakingSharedState.selectionItem
        .debounce(50)
        .onEach {
            stakingStateScope.coroutineContext.cancelChildren()
        }
        .map { stakingScenario.getViewModel(it.type) }
        .shareIn(stakingStateScope, started = SharingStarted.Eagerly, replay = 1)

    val networkInfo = scenarioViewModelFlow
        .flatMapLatest {
            it.networkInfo()
        }.distinctUntilChanged().shareIn(stakingStateScope, started = SharingStarted.Eagerly, replay = 1)

    val stakingViewState = scenarioViewModelFlow
        .flatMapLatest {
            it.getStakingViewStateFlow().withLoading()
        }.distinctUntilChanged().shareIn(stakingStateScope, started = SharingStarted.Eagerly, replay = 1)

    val stakingStateFlow = scenarioViewModelFlow
        .flatMapLatest {
            it.stakingStateFlow.withLoading()
        }.distinctUntilChanged().shareIn(stakingStateScope, started = SharingStarted.Eagerly, replay = 1)

    val alertsFlow = scenarioViewModelFlow
        .flatMapLatest {
            it.alerts()
        }.distinctUntilChanged().shareIn(stakingStateScope, started = SharingStarted.Eagerly, replay = 1)

    @OptIn(FlowPreview::class)
    private val estimatedEarningsViewState = stakingViewState.map {
        if (it is LoadingState.Loading) {
            return@map flowOf(
                EstimatedEarningsViewState(
                    monthlyChange = null,
                    yearlyChange = null
                )
            )
        }
        when (val state = (it as LoadingState.Loaded).data) {
            is StakingPoolWelcomeViewState -> {
                state.returns
            }
            else -> null
        }?.map { returns ->
            EstimatedEarningsViewState(
                monthlyChange = TitleValueViewState(returns.monthly.gain, returns.monthly.amount, returns.monthly.fiatAmount),
                yearlyChange = TitleValueViewState(returns.yearly.gain, returns.yearly.amount, returns.yearly.fiatAmount)
            )
        } ?: flowOf(null)
    }.flattenMerge()

    private val defaultNetworkInfoStates = mapOf(
        StakingType.POOL to StakingAssetInfoViewState.StakingPool.default(resourceManager),
        StakingType.RELAYCHAIN to StakingAssetInfoViewState.RelayChain.default(resourceManager),
        StakingType.PARACHAIN to StakingAssetInfoViewState.Parachain.default(resourceManager)
    )

    inline fun <reified T : StakingAssetInfoViewState> Map<StakingType, StakingAssetInfoViewState>.get(type: StakingType): T = get(type) as T

    private val networkInfoState = networkInfo.map { networkInfoState ->
        val selection = stakingSharedState.selectionItem.first()
        if (selection.type != StakingType.POOL) return@map null
        if (networkInfoState is LoadingState.Loaded) {
            networkInfoState.data as StakingAssetInfoViewState.
            defaultNetworkInfoStates[selection.type]!!.update(networkInfoState.data)
        } else {
            defaultNetworkInfoStates[selection.type]!!
        }
    }

    val state = combine(
        assetSelectorMixin.selectedAssetModelFlow,
        networkInfoState,
        estimatedEarningsViewState
    ) { selectedAsset, networkInfo, estimatedEarnings ->
        val selectorState = AssetSelectorState(
            selectedAsset.tokenName,
            selectedAsset.imageUrl,
            selectedAsset.assetBalance,
            (selectedAsset.selectionItem as? StakingAssetSelection.Pool)?.let { "pool" }
        )

        ViewState(
            selectorState,
            networkInfo,
            estimatedEarnings
        )
    }.stateIn(scope = this, started = SharingStarted.Eagerly, initialValue = null)

    init {
        stakingUpdateSystem.start()
            .launchIn(this)
        viewModelScope.launch {
            stakingSharedState.selectionItem.distinctUntilChanged().collect {
                setupStakingSharedState.set(SetupStakingProcess.Initial(it.type))
                stakingStateScope.coroutineContext.cancelChildren()
            }
        }
    }

    private val selectedChain = interactor.selectedChainFlow()
        .share()

    val stories = interactor.stakingStoriesFlow()
        .map { it.map(::transformStories) }
        .asLiveData()

    val currentAddressModelLiveData = currentAddressModelFlow().asLiveData()

    val networkInfoTitle = selectedChain
        .map { it.name }
        .share()

    fun storyClicked(group: StoryGroupModel) {
        if (group.stories.isNotEmpty()) {
            router.openStory(group)
        }
    }

    fun avatarClicked() {
//        router.openChangeAccountFromStaking()
        launch {
            val meta = accountInteractor.selectedMetaAccountFlow().first()
            val chain = interactor.getChain(kusamaChainId)
            val accountId = meta.accountId(chain)!!
            val poolMembers = stakingPoolInteractor.getPoolMembers(kusamaChainId, accountId)
            hashCode()
        }
    }

    override fun openCurrentValidators() {
        router.openCurrentValidators()
    }

    override fun openChangeValidators() {
        setupStakingSharedState.set(SetupStakingProcess.SelectBlockProducersStep.Validators(SetupStakingProcess.SelectBlockProducersStep.Payload.ExistingStash))
        router.openStartChangeValidators()
    }

    override fun bondMoreAlertClicked() {
        stakingStateScope.launch {
            val vm = scenarioViewModelFlow.first()
            val validation = vm.getBondMoreValidationSystem()
            requireValidManageStakingAction(validation) {
                val bondMorePayload = SelectBondMorePayload(overrideFinishAction = StakingRouter::returnToMain, collatorAddress = null)

                router.openBondMore(bondMorePayload)
            }
        }
    }

    override fun redeemAlertClicked() {
        stakingStateScope.launch {
            val vm = scenarioViewModelFlow.first()
            val validation = vm.getRedeemValidationSystem()
            requireValidManageStakingAction(validation) {
                val redeemPayload = RedeemPayload(overrideFinishAction = StakingRouter::back, collatorAddress = null)

                router.openRedeem(redeemPayload)
            }
        }
    }

    private suspend fun requireValidManageStakingAction(
        validationSystem: ManageStakingValidationSystem,
        action: () -> Unit
    ) {
        val viewModel = stakingScenario.viewModel.first()

        val stakingState = viewModel.stakingStateFlow.first()
        val stashState = stakingState as? StakingState.Stash ?: return

        validationExecutor.requireValid(
            validationSystem,
            ManageStakingValidationPayload(stashState),
            validationFailureTransformer = { manageStakingActionValidationFailure(it, resourceManager) }
        ) {
            action()
        }
    }

    private fun currentAddressModelFlow(): Flow<AddressModel> {
        return interactor.selectedAccountProjectionFlow().map {
            interactor.getWalletAddressModel(CURRENT_ICON_SIZE)
        }
    }

    fun onStakingBalance(model: DelegatorViewState.CollatorDelegationModel) {
        openStakingBalance(model.collatorAddress)
    }

    override fun openStakingBalance(collatorAddress: String) {
        router.openStakingBalance(collatorAddress)
    }

    fun openCollatorInfo(model: DelegatorViewState.CollatorDelegationModel) {
        viewModelScope.launch {
            val stakingState = stakingViewState.filterIsInstance<LoadingState.Loaded<DelegatorViewState>>().first()
            (stakingState as? LoadingState.Loaded)?.data?.openCollatorInfo(model)
        }
    }

    fun onEstimatedEarningsInfoClick(){

    }

    data class ViewState(
        val selectorState: AssetSelectorState,
        val networkInfoState: StakingAssetInfoViewState?, // todo shouldn't be nullable - it's just a stub
        val estimatedEarnings: EstimatedEarningsViewState? // todo shouldn't be nullable - it's just a stub
    )
}
