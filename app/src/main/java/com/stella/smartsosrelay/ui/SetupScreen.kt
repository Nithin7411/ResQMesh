package com.stella.smartsosrelay.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(viewModel: SosViewModel, onSetupComplete: () -> Unit) {
    val user     by viewModel.user.collectAsState()
    val contacts by viewModel.contacts.collectAsState()

    // Google Sign-In state
    val isGoogleSignedIn by viewModel.isGoogleSignedIn.collectAsState()
    val googleName by viewModel.googleDisplayName.collectAsState()
    val googleEmail by viewModel.googleEmail.collectAsState()

    // Firestore userId
    val firestoreUserId by viewModel.firestoreUserId.collectAsState()

    // Pre-fill with existing data if available
    var nameInput  by remember(user, googleName) {
        mutableStateOf(if (isGoogleSignedIn && googleName != null) googleName!! else user?.name ?: "")
    }
    var phoneInput by remember(user) { mutableStateOf(user?.phoneNumber ?: "") }

    var contactNameInput  by remember { mutableStateOf("") }
    var contactPhoneInput by remember { mutableStateOf("") }

    // Whether the user is updating an already-registered profile
    val isUpdatingProfile = user != null

    // Google Sign-In launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.handleGoogleSignInResult(result.data)
        }
    }

    // Initialize Google Auth on first composition
    LaunchedEffect(Unit) {
        viewModel.initGoogleAuth()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isUpdatingProfile) "Update Profile" else "Setup SOS") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D0D0D),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0D0D0D)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Firestore User ID Badge ──────────────────────────────────────
            if (firestoreUserId.isNotBlank()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1A1A2E)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "YOUR SOS ID",
                                    color = Color(0xFF8AB4F8),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                )
                                Text(
                                    text = firestoreUserId,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFF1B5E20), Color(0xFF2E7D32))
                                        )
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    "✓ Synced",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // ── Google Sign-In section ───────────────────────────────────────
            item {
                if (isGoogleSignedIn) {
                    // Signed in — show Google profile badge
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1B3A2D)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "✓ Signed in with Google",
                                    color = Color(0xFF66BB6A),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = googleName ?: "",
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = googleEmail ?: "",
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                            TextButton(onClick = { viewModel.googleSignOut() }) {
                                Text("Sign Out", color = Color(0xFFEF5350))
                            }
                        }
                    }
                } else {
                    // Not signed in — show sign-in button
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1A1A2E)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Quick Registration",
                                color = Color(0xFF8AB4F8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Sign in with Google to auto-create your profile\nor register manually below",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = {
                                    val intent = viewModel.getGoogleSignInIntent()
                                    if (intent != null) {
                                        googleSignInLauncher.launch(intent)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White
                                )
                            ) {
                                Text("🔑  Sign in with Google", fontSize = 15.sp)
                            }
                        }
                    }
                }
            }

            // ── "Updating profile" banner ────────────────────────────────────
            if (isUpdatingProfile) {
                item {
                    AnimatedVisibility(
                        visible = true,
                        enter   = fadeIn(),
                        exit    = fadeOut()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1A2A3A))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Info,
                                contentDescription = null,
                                tint               = Color(0xFF8AB4F8)
                            )
                            Column {
                                Text(
                                    text  = "Updating existing profile",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                                Text(
                                    text  = (if (user?.phoneNumber?.isNotBlank() == true) "Phone: ${user?.phoneNumber} · " else "") +
                                            if (user?.isRegistered == true) "✓ Synced to server"
                                            else "⏳ Not yet synced",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }

            // ── Personal information ─────────────────────────────────────────
            item {
                Text(
                    "Personal Information",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            item {
                OutlinedTextField(
                    value       = nameInput,
                    onValueChange = { nameInput = it },
                    label       = { Text("Your Name") },
                    modifier    = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFF333355),
                        focusedBorderColor = Color(0xFF8AB4F8),
                        unfocusedLabelColor = Color.Gray,
                        focusedLabelColor = Color(0xFF8AB4F8),
                        cursorColor = Color(0xFF8AB4F8),
                        unfocusedTextColor = Color.White,
                        focusedTextColor = Color.White
                    )
                )
            }

            item {
                OutlinedTextField(
                    value       = phoneInput,
                    onValueChange = { phoneInput = it },
                    label       = { Text("Your Phone Number") },
                    // Phone is the unique key — lock it after first registration
                    // so users can't accidentally create a second account
                    enabled     = !isUpdatingProfile || user?.phoneNumber.isNullOrBlank(),
                    modifier    = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFF333355),
                        focusedBorderColor = Color(0xFF8AB4F8),
                        unfocusedLabelColor = Color.Gray,
                        focusedLabelColor = Color(0xFF8AB4F8),
                        cursorColor = Color(0xFF8AB4F8),
                        unfocusedTextColor = Color.White,
                        focusedTextColor = Color.White,
                        disabledBorderColor = Color(0xFF222244),
                        disabledLabelColor = Color(0xFF555555),
                        disabledTextColor = Color(0xFF888888)
                    )
                )

                if (isUpdatingProfile && user?.phoneNumber?.isNotBlank() == true) {
                    Text(
                        text  = "Phone number cannot be changed after registration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF666666),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }

            item {
                Button(
                    onClick  = { viewModel.saveUser(nameInput, phoneInput) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = nameInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1565C0),
                        disabledContainerColor = Color(0xFF222244)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (isUpdatingProfile) "Update My Info" else "Save My Info",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ── Trusted contacts ─────────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Trusted Contacts",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value         = contactNameInput,
                        onValueChange = { contactNameInput = it },
                        label         = { Text("Name") },
                        modifier      = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color(0xFF333355),
                            focusedBorderColor = Color(0xFFFFA726),
                            unfocusedLabelColor = Color.Gray,
                            focusedLabelColor = Color(0xFFFFA726),
                            cursorColor = Color(0xFFFFA726),
                            unfocusedTextColor = Color.White,
                            focusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value         = contactPhoneInput,
                        onValueChange = { contactPhoneInput = it },
                        label         = { Text("Phone") },
                        modifier      = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color(0xFF333355),
                            focusedBorderColor = Color(0xFFFFA726),
                            unfocusedLabelColor = Color.Gray,
                            focusedLabelColor = Color(0xFFFFA726),
                            cursorColor = Color(0xFFFFA726),
                            unfocusedTextColor = Color.White,
                            focusedTextColor = Color.White
                        )
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        if (contactNameInput.isNotBlank() && contactPhoneInput.isNotBlank()) {
                            viewModel.addContact(contactNameInput, contactPhoneInput)
                            contactNameInput  = ""
                            contactPhoneInput = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE65100)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("+ Add Trusted Contact", fontWeight = FontWeight.Bold)
                }
            }

            // ── Contact list ─────────────────────────────────────────────────
            items(contacts) { contact ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1A1A2E)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                contact.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                contact.phoneNumber,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        IconButton(onClick = { viewModel.removeContact(contact.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Contact",
                                tint = Color(0xFFEF5350)
                            )
                        }
                    }
                }
            }

            // ── Proceed button ───────────────────────────────────────────────
            if (user != null && contacts.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick  = onSetupComplete,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "✓ Proceed to Dashboard",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
