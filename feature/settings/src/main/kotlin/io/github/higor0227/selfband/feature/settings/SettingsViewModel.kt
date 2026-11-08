// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.higor0227.selfband.core.model.AppSettings
import io.github.higor0227.selfband.core.model.ProxyMode
import io.github.higor0227.selfband.core.project.AppSettingsRepository
import io.github.higor0227.selfband.core.video.ProxyGenerator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    /** Proxies waiting or being made. */
    val proxiesPending: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: AppSettingsRepository,
    proxies: ProxyGenerator,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> =
        combine(repository.settings, proxies.state) { settings, proxy ->
            SettingsUiState(settings, proxy.pending)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setProxyMode(mode: ProxyMode) {
        viewModelScope.launch { repository.update { it.copy(proxyMode = mode) } }
    }
}
