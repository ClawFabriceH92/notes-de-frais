package com.trucdecomptable.notesdefrais.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.trucdecomptable.notesdefrais.export.ExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The attachments stored in filesDir/justificatifs never had a screen of their
 * own: an expense could be photographed, exported, and still be invisible in
 * the app. These helpers show them (thumbnail + full screen) and hand a PDF to
 * the system viewer.
 */

private fun fichierDe(context: Context, nom: String): File =
    File(ExportManager.dossierJustificatifs(context), nom)

private fun estPdf(nom: String): Boolean = nom.endsWith(".pdf", ignoreCase = true)

/** Decodes a stored piece with a bounding box, so a 12 MPx photo stays cheap. */
private fun decoderAvecPlafond(fichier: File, maxPx: Int): ImageBitmap? {
    if (!fichier.exists()) return null
    val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(fichier.absolutePath, bornes)
    if (bornes.outWidth <= 0 || bornes.outHeight <= 0) return null
    var facteur = 1
    while (bornes.outWidth / facteur > maxPx || bornes.outHeight / facteur > maxPx) facteur *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = facteur }
    val bitmap = BitmapFactory.decodeFile(fichier.absolutePath, options) ?: return null
    return bitmap.asImageBitmap()
}

/** Hands the piece to whatever app can display it (image gallery, PDF reader). */
fun ouvrirPiece(context: Context, nom: String) {
    val fichier = fichierDe(context, nom)
    if (!fichier.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fichier)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, if (estPdf(nom)) "application/pdf" else "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

/**
 * A tappable thumbnail of a stored piece. A PDF shows its icon instead of a
 * preview: nothing renders a PDF page without a heavy dependency.
 */
@Composable
fun VignettePiece(
    nom: String,
    modifier: Modifier = Modifier,
    taille: Dp = 56.dp,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var image by remember(nom) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(nom) {
        image = withContext(Dispatchers.IO) {
            if (estPdf(nom)) null else decoderAvecPlafond(fichierDe(context, nom), 320)
        }
    }

    val forme = RoundedCornerShape(8.dp)
    Box(
        modifier
            .size(taille)
            .clip(forme)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val apercu = image
        when {
            apercu != null -> Image(
                bitmap = apercu,
                contentDescription = "Pièce jointe",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            estPdf(nom) -> Icon(
                Icons.Default.PictureAsPdf,
                contentDescription = "Pièce jointe PDF",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(taille / 2),
            )

            else -> Icon(
                Icons.Default.PictureAsPdf,
                contentDescription = "Pièce jointe illisible",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(taille / 2),
            )
        }
    }
}

/**
 * Full screen view of one piece: pinch-to-zoom is left out on purpose, the
 * photo alone is what was missing. Tapping the background closes it.
 */
@Composable
fun ApercuPiece(nom: String, onFermer: () -> Unit) {
    val context = LocalContext.current
    var image by remember(nom) { mutableStateOf<ImageBitmap?>(null) }
    var charge by remember(nom) { mutableStateOf(true) }

    LaunchedEffect(nom) {
        image = withContext(Dispatchers.IO) {
            if (estPdf(nom)) null else decoderAvecPlafond(fichierDe(context, nom), 2000)
        }
        charge = false
    }

    Dialog(
        onDismissRequest = onFermer,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onFermer),
            contentAlignment = Alignment.Center,
        ) {
            val apercu = image
            when {
                apercu != null -> Image(
                    bitmap = apercu,
                    contentDescription = "Pièce jointe",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )

                charge -> Text("Chargement…", color = Color.White)

                estPdf(nom) -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(72.dp),
                    )
                    Text("Pièce jointe PDF", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        nom,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { ouvrirPiece(context, nom) }) { Text("Ouvrir avec…") }
                }

                else -> Text("Pièce introuvable", color = Color.White)
            }

            Row(
                Modifier.fillMaxWidth().align(Alignment.TopEnd).padding(8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onFermer) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer", tint = Color.White)
                }
            }
        }
    }
}
