package dev.opencode.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.opencode.mobile.lifecycle.ForegroundEventLifecycleCoordinator
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  @Inject lateinit var foregroundEventLifecycleCoordinator: ForegroundEventLifecycleCoordinator

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    foregroundEventLifecycleCoordinator.register()

    enableEdgeToEdge()
    setContent { MainNavigation() }
  }
}
