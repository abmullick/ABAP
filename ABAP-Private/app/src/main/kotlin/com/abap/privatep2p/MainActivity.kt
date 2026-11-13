package com.abap.privatep2p

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.abap.privatep2p.core.identity.AndroidDeviceIdentityStore
import com.abap.privatep2p.feature.pairing.EncryptedPairingStore
import com.abap.privatep2p.feature.pairing.PairingCoordinator
import com.abap.privatep2p.feature.pairing.PairingInvitation
import com.abap.privatep2p.feature.pairing.PairingQrCode
import com.abap.privatep2p.feature.pairing.PairingResponse
import com.abap.privatep2p.feature.pairing.PinnedPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ABAPPrivateApp() }
    }
}

private enum class PairingScreen {
    HOME,
    SCAN_INVITATION,
    CONFIRM_INVITER,
    SHOW_RESPONSE,
    SCAN_RESPONSE,
    CONFIRM_RESPONDER,
    PAIRED
}

@Composable
private fun ABAPPrivateApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identityStore = remember { AndroidDeviceIdentityStore() }
    val pairing = remember { PairingCoordinator(identityStore::getOrCreate) }
    val pairingStore = remember(context) { EncryptedPairingStore(context) }

    var screen by remember { mutableStateOf(PairingScreen.HOME) }
    var initialized by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("Initialising secure device identity…") }
    var deviceFingerprint by remember { mutableStateOf<String?>(null) }
    var activeInvitation by remember { mutableStateOf<PairingInvitation?>(null) }
    var invitationQr by remember { mutableStateOf<Bitmap?>(null) }
    var invitationExpiry by remember { mutableStateOf<String?>(null) }
    var responsePayload by remember { mutableStateOf<String?>(null) }
    var responseQr by remember { mutableStateOf<Bitmap?>(null) }
    var scannedInvitation by remember { mutableStateOf<PairingInvitation?>(null) }
    var scannedResponse by remember { mutableStateOf<PairingResponse?>(null) }
    var candidatePeer by remember { mutableStateOf<PinnedPeer?>(null) }
    var pinnedPeer by remember { mutableStateOf<PinnedPeer?>(null) }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
        if (!granted) errorMessage = "Camera permission is required to scan pairing QR codes."
    }

    fun runTask(task: suspend () -> Unit) {
        scope.launch {
            busy = true
            errorMessage = null
            try {
                task()
            } catch (error: Exception) {
                errorMessage = error.message ?: "Pairing operation failed"
            } finally {
                busy = false
            }
        }
    }

    fun deviceName(): String =
        (Build.MODEL ?: "Android device").trim().take(48).ifBlank { "Android device" }

    fun createFreshInvitation() {
        runTask {
            val result = withContext(Dispatchers.IO) {
                val invitation = pairing.createInvitation(deviceName())
                val payload = pairing.encodeInvitation(invitation)
                Triple(invitation, payload, PairingQrCode.render(payload))
            }
            activeInvitation = result.first
            invitationQr = result.third
            invitationExpiry = Instant.ofEpochSecond(result.first.expiresAtEpochSeconds)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            statusMessage = "Invitation ready. It expires at ${invitationExpiry}."
            screen = PairingScreen.HOME
        }
    }

    LaunchedEffect(Unit) {
        busy = true
        try {
            val result = withContext(Dispatchers.IO) {
                val identity = identityStore.getOrCreate()
                val peer = pairingStore.loadPeer()
                val pending = pairingStore.loadPendingResponse()
                val invitation = if (peer == null) pairing.createInvitation(deviceName()) else null
                val payload = invitation?.let(pairing::encodeInvitation)
                val qr = payload?.let { PairingQrCode.render(it) }
                val pendingQr = pending?.let { PairingQrCode.render(it.encodedPayload) }
                InitialPairingState(identity.fingerprint, peer, invitation, qr, pending, pendingQr)
            }
            deviceFingerprint = result.fingerprint
            pinnedPeer = result.peer
            activeInvitation = result.invitation
            invitationQr = result.invitationQr
            result.invitation?.let {
                invitationExpiry = Instant.ofEpochSecond(it.expiresAtEpochSeconds)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            }
            if (result.pendingResponse != null) {
                responsePayload = result.pendingResponse.encodedPayload
                responseQr = result.pendingResponseQr
                screen = PairingScreen.SHOW_RESPONSE
                statusMessage = "A pairing response is saved. Show this QR to the initiating phone."
            } else {
                statusMessage = if (result.peer == null) {
                    "Your device identity is ready. Share your invitation QR or scan another device."
                } else {
                    "This device has one pinned peer."
                }
            }
            initialized = true
        } catch (error: Exception) {
            errorMessage = error.message ?: "Could not initialise device identity"
        } finally {
            busy = false
        }
    }

    fun handleScannedQr(payload: String) {
        runTask {
            when (screen) {
                PairingScreen.SCAN_INVITATION -> {
                    val invitation = withContext(Dispatchers.IO) {
                        pairing.decodeInvitation(payload).also {
                            check(!pairingStore.isInvitationConsumed(it.invitationId)) {
                                "This invitation has already been used on this device."
                            }
                        }
                    }
                    scannedInvitation = invitation
                    candidatePeer = withContext(Dispatchers.IO) {
                        pairing.peerFromInvitation(
                            invitation,
                            AndroidDeviceIdentityStore.fingerprint(invitation.publicKey)
                        )
                    }
                    screen = PairingScreen.CONFIRM_INVITER
                    statusMessage = "Invitation signature is valid. Compare the fingerprint before confirming."
                }

                PairingScreen.SCAN_RESPONSE -> {
                    val invitation = activeInvitation
                        ?: error("No active invitation exists. Create a fresh invitation first.")
                    val response = pairing.decodeResponse(payload)
                    val candidate = withContext(Dispatchers.IO) {
                        pairing.inspectResponse(invitation, response)
                    }
                    scannedResponse = response
                    candidatePeer = candidate
                    screen = PairingScreen.CONFIRM_RESPONDER
                    statusMessage = "Response signature and invitation binding are valid. Verify the fingerprint."
                }

                else -> error("The scanner is not active.")
            }
        }
    }

    fun confirmInviter() {
        val invitation = scannedInvitation ?: return
        val fingerprint = candidatePeer?.fingerprint ?: return
        runTask {
            val result = withContext(Dispatchers.IO) {
                val response = pairing.createResponse(invitation, deviceName(), fingerprint)
                val payload = pairing.encodeResponse(response)
                val peer = pairing.peerFromInvitation(invitation, fingerprint)
                pairingStore.completePairing(peer, invitation.invitationId, payload)
                Triple(peer, payload, PairingQrCode.render(payload))
            }
            pinnedPeer = result.first
            responsePayload = result.second
            responseQr = result.third
            scannedInvitation = null
            candidatePeer = null
            screen = PairingScreen.SHOW_RESPONSE
            statusMessage = "Your peer is pinned locally. Keep this response QR available for the initiating phone."
        }
    }

    fun confirmResponder() {
        val invitation = activeInvitation ?: return
        val response = scannedResponse ?: return
        val fingerprint = candidatePeer?.fingerprint ?: return
        runTask {
            val peer = withContext(Dispatchers.IO) {
                val verifiedPeer = pairing.verifyResponse(invitation, response, fingerprint)
                pairingStore.completePairing(verifiedPeer, invitation.invitationId)
                verifiedPeer
            }
            pinnedPeer = peer
            scannedResponse = null
            candidatePeer = null
            activeInvitation = null
            invitationQr = null
            screen = PairingScreen.PAIRED
            statusMessage = "Both device identities have been verified and pinned on this phone."
        }
    }

    fun revokeLocalPeer() {
        runTask {
            withContext(Dispatchers.IO) { pairingStore.revokePeer() }
            pinnedPeer = null
            responsePayload = null
            responseQr = null
            activeInvitation = null
            invitationQr = null
            statusMessage = "Local peer trust revoked. The other device is not notified; pair again manually."
            screen = PairingScreen.HOME
            createFreshInvitation()
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "ABAP Private",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text("Two devices. One private connection.", style = MaterialTheme.typography.bodyMedium)

                if (!initialized && busy) {
                    CircularProgressIndicator()
                    Text(statusMessage)
                } else {
                    deviceFingerprint?.let {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("This device fingerprint", fontWeight = FontWeight.SemiBold)
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    when (screen) {
                        PairingScreen.HOME -> {
                            if (pinnedPeer == null) {
                                Text("Pair your two devices", style = MaterialTheme.typography.titleLarge)
                                invitationQr?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Signed pairing invitation QR code",
                                        modifier = Modifier.size(250.dp)
                                    )
                                } ?: CircularProgressIndicator()
                                Text(
                                    "Show this invitation QR to the second phone. The invitation is signed by this device and expires at ${invitationExpiry ?: "the displayed time"}.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = { screen = PairingScreen.SCAN_INVITATION },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Scan another device's invitation") }
                                OutlinedButton(
                                    onClick = { screen = PairingScreen.SCAN_RESPONSE },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = activeInvitation != null
                                ) { Text("Scan pairing response") }
                                OutlinedButton(
                                    onClick = { createFreshInvitation() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Refresh invitation QR") }
                            } else {
                                PeerCard(pinnedPeer!!)
                                Text(
                                    "This is a local trust record only. It does not mean a direct network connection is available.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (responseQr != null) {
                                    OutlinedButton(
                                        onClick = { screen = PairingScreen.SHOW_RESPONSE },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Show saved response QR") }
                                }
                                OutlinedButton(
                                    onClick = { revokeLocalPeer() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Revoke this peer on this device") }
                            }
                        }

                        PairingScreen.SCAN_INVITATION, PairingScreen.SCAN_RESPONSE -> {
                            Text(
                                if (screen == PairingScreen.SCAN_INVITATION) "Scan invitation" else "Scan response",
                                style = MaterialTheme.typography.titleLarge
                            )
                            if (!cameraPermissionGranted) {
                                Text("Camera access is needed only while scanning a QR code.")
                                Button(
                                    onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Grant camera permission") }
                                OutlinedButton(
                                    onClick = { screen = PairingScreen.HOME },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Back") }
                            } else {
                                CameraQrScanner(
                                    onQrCode = ::handleScannedQr,
                                    onCancel = { screen = PairingScreen.HOME }
                                )
                            }
                        }

                        PairingScreen.CONFIRM_INVITER -> {
                            Text("Verify the inviting device", style = MaterialTheme.typography.titleLarge)
                            candidatePeer?.let { PeerCard(it) }
                            Text(
                                "Compare this complete fingerprint with the fingerprint shown on the other phone using a separate trusted channel. Do not confirm if even one character differs.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = ::confirmInviter,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy && candidatePeer != null
                            ) { Text("I compared and confirmed this fingerprint") }
                            OutlinedButton(
                                onClick = { screen = PairingScreen.HOME },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Reject invitation") }
                        }

                        PairingScreen.SHOW_RESPONSE -> {
                            Text("Pairing response", style = MaterialTheme.typography.titleLarge)
                            responseQr?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Signed pairing response QR code",
                                    modifier = Modifier.size(250.dp)
                                )
                            } ?: CircularProgressIndicator()
                            Text(
                                "The other phone must scan this response QR to finish mutual pairing. This response is saved encrypted on this device so you can return to it later.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = { screen = PairingScreen.HOME },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Back to paired device") }
                        }

                        PairingScreen.CONFIRM_RESPONDER -> {
                            Text("Verify the responding device", style = MaterialTheme.typography.titleLarge)
                            candidatePeer?.let { PeerCard(it) }
                            Text(
                                "The response signature and invitation binding have been checked. Compare this fingerprint with the second phone over a separate trusted channel before pinning it.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = ::confirmResponder,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy && candidatePeer != null
                            ) { Text("I compared and confirmed this fingerprint") }
                            OutlinedButton(
                                onClick = { screen = PairingScreen.HOME },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Reject response") }
                        }

                        PairingScreen.PAIRED -> {
                            pinnedPeer?.let { PeerCard(it) }
                            Text("Pairing complete on this device.", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "No network rendezvous or connection has been attempted. The app remains strictly serverless.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = { screen = PairingScreen.HOME },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Continue") }
                        }
                    }
                }

                if (busy && initialized) CircularProgressIndicator()
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(statusMessage, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pairing QR codes contain signed identity data only. No server, signalling service, STUN/TURN or relay is used.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun PeerCard(peer: PinnedPeer) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(peer.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("SHA-256 identity fingerprint", style = MaterialTheme.typography.labelMedium)
            Text(peer.fingerprint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class InitialPairingState(
    val fingerprint: String,
    val peer: PinnedPeer?,
    val invitation: PairingInvitation?,
    val invitationQr: Bitmap?,
    val pendingResponse: com.abap.privatep2p.feature.pairing.PendingPairingResponse?,
    val pendingResponseQr: Bitmap?
)
