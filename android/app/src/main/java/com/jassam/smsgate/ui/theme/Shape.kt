package com.jassam.smsgate.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Dashboard radii: cards rounded-2xl (16), controls rounded-xl (12), small buttons rounded-lg (8). */
object Radii {
    val Card = RoundedCornerShape(16.dp)
    val Control = RoundedCornerShape(12.dp)
    val Small = RoundedCornerShape(8.dp)
    val LogoTile = RoundedCornerShape(10.dp)
    val Pill = CircleShape
}

val SmsGateShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = Radii.Small,
    medium = Radii.Control,
    large = Radii.Card,
    extraLarge = RoundedCornerShape(24.dp),
)
