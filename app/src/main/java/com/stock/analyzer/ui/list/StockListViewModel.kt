package com.stock.analyzer.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stock.analyzer.data.model.Stock
import com.stock.analyzer.data.repository.StockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StockListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StockRepository(application)

    private val _stocks = MutableStateFlow<List<Stock>>(emptyList())
    val stocks: StateFlow<List<Stock>> = _stocks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // 默认显示的股票列表
    private val defaultCodes = listOf(
        "sh600519", "sh600036", "sh600030", "sz000858", "sz000651",
        "sz000333", "sh601318", "sh600276", "sh600048", "sz000002",
        "sh601888", "sz300750", "sz002594", "sz300059", "sh600900",
        "sz000001", "sh601398", "sh601288", "sh601939", "sh601012",
        "sz000725", "sz300015", "sh600887", "sz000568", "sz002304",
        "sh603288", "sz002415", "sz300760", "sh601166", "sz002142"
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val result = repository.getRealtimeQuotes(defaultCodes)
                result.onSuccess { stockList ->
                    _stocks.value = stockList
                }.onFailure { e ->
                    _error.value = "加载失败: ${e.message}"
                }
            } catch (e: Exception) {
                _error.value = "加载失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
