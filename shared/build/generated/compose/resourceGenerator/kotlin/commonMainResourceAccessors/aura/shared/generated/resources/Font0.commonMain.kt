@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package aura.shared.generated.resources

import kotlin.OptIn
import kotlin.String
import kotlin.collections.MutableMap
import org.jetbrains.compose.resources.FontResource
import org.jetbrains.compose.resources.InternalResourceApi

private object CommonMainFont0 {
  public val outfit: FontResource by 
      lazy { init_outfit() }
}

@InternalResourceApi
internal fun _collectCommonMainFont0Resources(map: MutableMap<String, FontResource>) {
  map.put("outfit", CommonMainFont0.outfit)
}

internal val Res.font.outfit: FontResource
  get() = CommonMainFont0.outfit

private fun init_outfit(): FontResource = org.jetbrains.compose.resources.FontResource(
  "font:outfit",
    setOf(
      org.jetbrains.compose.resources.ResourceItem(setOf(),
    "composeResources/aura.shared.generated.resources/font/outfit.ttf", -1, -1),
    )
)
