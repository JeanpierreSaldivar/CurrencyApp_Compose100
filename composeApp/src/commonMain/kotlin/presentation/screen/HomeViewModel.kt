package presentation.screen

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import domain.CurrencyApiService
import domain.MongoRepository
import domain.PreferencesRepository
import domain.model.Currency
import domain.model.RateStatus
import domain.model.RequestState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

sealed class HomeUiEvent {
    data object RefreshRates: HomeUiEvent()
    data object SwitchCurrencies: HomeUiEvent()
    data class SaveSourceCurrencyCode(val code: String): HomeUiEvent()
    data class SaveTargetCurrencyCode(val code: String): HomeUiEvent()
}

class HomeViewModel(
    private val preferences : PreferencesRepository,
    private val mongoDb : MongoRepository,
    private val api : CurrencyApiService
): ScreenModel{
    private  var _rateStatus : MutableState<RateStatus> =
        mutableStateOf(RateStatus.Idle)
    val rateStatus: State<RateStatus> = _rateStatus

    private var _allCurrencies = mutableStateListOf<Currency>()
    val allCurrencies: List<Currency> = _allCurrencies

    private  var _sourceCurrency : MutableState<RequestState<Currency>> =
        mutableStateOf(RequestState.Idle)
    val sourceCurrency: State<RequestState<Currency>>  = _sourceCurrency

    private  var _targetCurrency: MutableState<RequestState<Currency>>  =
        mutableStateOf(RequestState.Idle)
    val targetCurrency: State<RequestState<Currency>>  = _targetCurrency

    init {
        screenModelScope.launch {
            fetchNewRates()
            readSourceCurrency()
            readTargetCurrency()
        }
    }

    fun sendEvent(event : HomeUiEvent){
        when(event){
            HomeUiEvent.RefreshRates -> {
                screenModelScope.launch {
                    fetchNewRates()
                }
            }

            HomeUiEvent.SwitchCurrencies -> {
                switchCurrencies()
            }

            is HomeUiEvent.SaveSourceCurrencyCode ->{
                saveSourceCurrencyCode(event.code)
            }
            is HomeUiEvent.SaveTargetCurrencyCode ->{
                saveTargetCurrencyCode(event.code)
            }
        }
    }

    private fun readSourceCurrency(){
        screenModelScope.launch(Dispatchers.Main) {
            preferences.readSourceCurrencyCode().collectLatest {currencyCode ->
                val selectCurrency = _allCurrencies.find { it.code == currencyCode.name }
                if (selectCurrency != null){
                    _sourceCurrency.value = RequestState.Success(data = selectCurrency)
                } else  {
                    _sourceCurrency.value = RequestState.Error(message = "Couldn't find the selected currency.")
                }
            }
        }
    }

    private fun readTargetCurrency(){
        screenModelScope.launch(Dispatchers.Main) {
            preferences.readTargetCurrencyCode().collectLatest {currencyCode ->
                val selectCurrency = _allCurrencies.find { it.code == currencyCode.name }
                if (selectCurrency != null){
                    _targetCurrency.value = RequestState.Success(data = selectCurrency)
                } else  {
                    _targetCurrency.value = RequestState.Error(message = "Couldn't find the selected currency.")
                }
            }
        }
    }

    private suspend fun fetchNewRates() {
        try {
            val localCache = mongoDb.readCurrencyData().first()
            if(localCache.isSuccess()){
                if (localCache.getSuccessData().isNotEmpty()){
                    println("HomeViewModel: DATABASE IS FULL")
                    _allCurrencies.clear()
                    val data = localCache.getSuccessData()
                    _allCurrencies.addAll(data)
                    if (!preferences.isDataFresh(Clock.System.now().toEpochMilliseconds())){
                        println("HomeViewModel: DATA NOT FRESH")
                        cacheTheData()
                    }else{
                        println("HomeViewModel: DATA IS FRESH")
                    }
                }else{
                    println("HomeViewModel: DATA NEEDS DATA")
                    cacheTheData()
                }
            } else if (localCache.isError()){
                println("HomeViewModel: ERROR READING LOCAL DATABASE ${localCache.getErrorMessage()}")
            }
            getRatesStatus()
        }catch (e: Exception){
            println(e.message)
        }
    }

    private suspend fun cacheTheData(){
        val fetchedData = api.getLatestExchangeRates()
        if (fetchedData.isSuccess()){
            mongoDb.cleanUp()
            fetchedData.getSuccessData().forEach {
                println("HomeViewModel: ADDING ${it.code}")
                mongoDb.insertCurrencyData(it)
            }
            println("HomeViewModel: UPDATING _allCurrencies")
            _allCurrencies.clear()
            val data = fetchedData.getSuccessData()
            _allCurrencies.addAll(data)
        }else if (fetchedData.isError()){
            println("HomeViewModel: FETCHING FAILED ${fetchedData.getErrorMessage()}")
        }
    }

    private suspend fun getRatesStatus() {
        _rateStatus.value = if (preferences.isDataFresh(
            currentTimestamp = Clock.System.now().toEpochMilliseconds()
            )) RateStatus.Fresh
        else RateStatus.Stale
    }

    private fun switchCurrencies(){
        val source = _sourceCurrency.value
        val target = _targetCurrency.value
        _sourceCurrency.value = target
        _targetCurrency.value = source
    }

    private fun saveSourceCurrencyCode(code: String){
        screenModelScope.launch(Dispatchers.IO){
            preferences.saveSourceCurrencyCode(code)
        }
    }
    private fun saveTargetCurrencyCode(code: String){
        screenModelScope.launch(Dispatchers.IO){
            preferences.saveTargetCurrencyCode(code)
        }
    }
}