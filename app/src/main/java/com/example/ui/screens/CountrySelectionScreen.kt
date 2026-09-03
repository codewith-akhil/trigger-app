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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GeometricCanvasBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("country_selection_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (isSearching) {
                            isSearching = false
                            searchQuery = ""
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier.testTag("country_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFF44474E)
                    )
                }

                if (isSearching) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search_country),
                                color = GeometricTextMuted,
                                fontSize = 16.sp
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = GeometricTextDark,
                            unfocusedTextColor = GeometricTextDark,
                            cursorColor = GeometricGreenPrimary,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("country_search_input")
                    )

                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.testTag("clear_country_search")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear",
                                tint = Color(0xFF44474E)
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.choose_country),
                        color = GeometricTextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = { isSearching = true },
                        modifier = Modifier.testTag("country_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = Color(0xFF44474E)
                        )
                    }
                }
            }

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
