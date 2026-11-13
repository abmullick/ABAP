package com.abap.privatep2p

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abap.privatep2p.core.identity.AndroidDeviceIdentityStore
import com.abap.privatep2p.feature.pairing.PairingCoordinator
import com.abap.privatep2p.feature.pairing.PairingQrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ABAPPrivateApp() }
    }
}

@androidx.compose.runtime.Composable
private fun ABAPPrivateApp() {
    var status by remember { mutableStateOf("Preparing this device's identity…") }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var invitationQr by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                val identityStore = AndroidDeviceIdentityStore()
                val identity = identityStore.getOrCreate()
                val pairing = PairingCoordinator(identityStore::getOrCreate)
                val invitation = pairing.createInvitation(android.os.Build.MODEL ?: "Android device")
                Triple(
                    identity.fingerprint,
                    PairingQrCode.render(pairing.encodeInvitation(invitation)),
                    invitation.expiresAtEpochSeconds
                )
            }
        }.onSuccess { result ->
            fingerprint = result.first
            invitationQr = result.second
            status = "Invitation expires: " + Instant.ofEpochSecond(result.third)
        }.onFailure { error ->
            status = "Could not initialise device identity: " + (error.message ?: "unknown error")
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("ABAP Private", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Two devices. One private connection.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(24.dp))
                if (invitationQr != null) {
                    Image(
                        bitmap = invitationQr!!.asImageBitmap(),
                        contentDescription = "Pairing invitation QR code",
                        modifier = Modifier.size(260.dp)
                    )
                } else {
                    CircularProgressIndicator()
                }
                Spacer(Modifier.height(16.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
                fingerprint?.let {
                    Spacer(Modifier.height(16.dp))
                    Text("This device fingerprint", fontWeight = FontWeight.SemiBold)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "QR bootstrap only. Scan/import and mutual confirmation screens are still being built. This QR does not create a network connection.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
