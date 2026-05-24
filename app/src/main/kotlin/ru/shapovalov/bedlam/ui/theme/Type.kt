package ru.shapovalov.bedlam.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import ru.shapovalov.bedlam.R

@OptIn(ExperimentalTextApi::class)
private fun robotoFlexFont(weight: FontWeight) = Font(
    resId = R.font.roboto_flex,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private val RobotoFlex = FontFamily(
    robotoFlexFont(FontWeight.Thin),
    robotoFlexFont(FontWeight.ExtraLight),
    robotoFlexFont(FontWeight.Light),
    robotoFlexFont(FontWeight.Normal),
    robotoFlexFont(FontWeight.Medium),
    robotoFlexFont(FontWeight.SemiBold),
    robotoFlexFont(FontWeight.Bold),
    robotoFlexFont(FontWeight.ExtraBold),
    robotoFlexFont(FontWeight.Black),
)

val BedlamTypography = Typography(fontFamily = RobotoFlex)
