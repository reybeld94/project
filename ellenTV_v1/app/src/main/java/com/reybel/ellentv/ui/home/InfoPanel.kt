package com.reybel.ellentv.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reybel.ellentv.data.api.EpgGridResponse
import com.reybel.ellentv.data.api.EpgProgram
import com.reybel.ellentv.data.api.LiveItem
import com.reybel.ellentv.ui.absUrl
import com.reybel.ellentv.ui.components.OptimizedAsyncImage
import com.reybel.ellentv.ui.epgDescription
import com.reybel.ellentv.ui.formatClock12
import com.reybel.ellentv.ui.pickNowNext
import java.time.Instant

@Composable
fun InfoPanel(
    browseLiveId: String?,
    selectedId: String?,
    browseProgram: EpgProgram?,
    epgGrid: EpgGridResponse?,
    channels: List<LiveItem>,
    now: Instant
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.22f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            val infoLiveId = browseLiveId ?: selectedId

            val channelMap = remember(channels) { channels.associateBy { it.id } }
            val epgItemMap = remember(epgGrid) {
                epgGrid?.items?.associateBy { it.liveId } ?: emptyMap()
            }

            val infoData = remember(infoLiveId, channelMap, epgItemMap) {
                derivedStateOf {
                    val infoItem = infoLiveId?.let { epgItemMap[it] }
                    val infoCh = infoLiveId?.let { channelMap[it] }

                    val headerLogo = absUrl(
                        infoCh?.customLogoUrl
                            ?: infoCh?.logo
                            ?: infoCh?.streamIcon
                            ?: infoItem?.logo
                    )

                    Triple(infoItem, infoCh, headerLogo)
                }
            }.value


            val (infoItem, infoCh, headerLogo) = infoData

            // Clock con formato pre-calculado
            val clockText = remember(now) {
                formatClock12(now)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!headerLogo.isNullOrBlank()) {
                    OptimizedAsyncImage(
                        url = headerLogo,
                        modifier = Modifier.size(34.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.width(10.dp))
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = clockText,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.widthIn(min = 90.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            val nowP = remember(infoItem, now) {
                derivedStateOf {
                    pickNowNext(infoItem?.programs ?: emptyList(), now).first
                }
            }.value

            val showProgram = browseProgram ?: nowP?.p

            if (showProgram == null) {
                Text(
                    "No program info",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.9f)
                )
            } else {
                val title = remember(showProgram) {
                    showProgram.title.ifBlank { "(sin título)" }
                }
                val desc = remember(showProgram) {
                    epgDescription(showProgram).ifBlank { "—" }
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 4
                )
            }
        }
    }
}