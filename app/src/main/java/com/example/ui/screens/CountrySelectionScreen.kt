package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.Country
import com.example.model.CountryRepository
import com.example.ui.theme.*

@Composable
fun CountrySelectionScreen(
    selectedCountry: Country,
    onCountrySelected: (Country) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    val filteredCountries = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            CountryRepository.countries
        } else {
            CountryRepository.countries.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.nativeName.contains(searchQuery, ignoreCase = true) ||
                it.dialCode.contains(searchQuery)
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("country_selection_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = GeometricCanvasBg,
        topBar = {
            if (isSearching) {
                Surface(
                    color = TriggerHeaderGreen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsTopHeight(WindowInsets.statusBars)
                                .background(TriggerHeaderGreen)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                isSearching = false
                                searchQuery = ""
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White
                                )
                            }
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.search_country),
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 16.sp
                                    )
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = Color.White,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("country_search_input")
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Clear",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                com.example.ui.components.TriggerTopHeader(
                    title = stringResource(R.string.choose_country),
                    onBack = onBack,
                    actions = {
                        IconButton(
                            onClick = { isSearching = true },
                            modifier = Modifier.testTag("country_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search",
                                tint = Color.White
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            com.example.ui.components.TriggerBottomNavInset()
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            HorizontalDivider(color = GeometricBorderLight, thickness = 1.dp)

            // Countries List
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("country_list")
            ) {
                items(filteredCountries, key = { it.name + it.dialCode }) { country ->
                    val isSelected = country.name == selectedCountry.name
                    CountryRowItem(
                        country = country,
                        isSelected = isSelected,
                        onClick = { onCountrySelected(country) }
                    )
                    HorizontalDivider(
                        color = GeometricBorderLight,
                        thickness = 0.8.dp,
                        modifier = Modifier.padding(start = 72.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CountryRowItem(
    country: Country,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag("country_item_${country.dialCode.replace("+", "")}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flag
        Text(
            text = country.flagEmoji,
            fontSize = 24.sp,
            modifier = Modifier.padding(end = 20.dp)
        )

        // Name & Native Name
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = country.name,
                color = if (isSelected) GeometricGreenPrimary else GeometricTextDark,
                fontSize = 16.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (country.nativeName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = country.nativeName,
                    color = GeometricTextSecondary,
                    fontSize = 13.5.sp
                )
            }
        }

        // Dial code & checkmark
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = country.dialCode,
                color = GeometricTextMuted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )

            if (isSelected) {
                Spacer(modifier = Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = GeometricGreenPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
