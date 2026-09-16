// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.config

/**
 * The sale screen's tap grid, standing in for a sheet of barcodes at the counter. Only ids and
 * labels live here: tapping one does a real lookup, so price and availability come from Astro Shop.
 * Ids match the catalog seed in src/postgresql/init.sql.
 */
data class BarcodeSheetEntry(val id: String, val name: String)

val BARCODE_SHEET: List<BarcodeSheetEntry> = listOf(
    BarcodeSheetEntry("OLJCESPC7Z", "National Park Foundation Explorascope"),
    BarcodeSheetEntry("66VCHSJNUP", "Starsense Explorer Refractor Telescope"),
    BarcodeSheetEntry("1YMWWN1N4O", "Eclipsmart Travel Refractor Telescope"),
    BarcodeSheetEntry("L9ECAV7KIM", "Lens Cleaning Kit"),
    BarcodeSheetEntry("2ZYFJ3GM2N", "Roof Binoculars"),
    BarcodeSheetEntry("0PUK6V6EV0", "Solar System Color Imager"),
    BarcodeSheetEntry("LS4PSXUNUM", "Red Flashlight"),
    BarcodeSheetEntry("9SIQT8TOJO", "Optical Tube Assembly"),
    BarcodeSheetEntry("6E92ZMYYFZ", "Solar Filter"),
    BarcodeSheetEntry("HQTGWGPNH4", "The Comet Book"),
)
