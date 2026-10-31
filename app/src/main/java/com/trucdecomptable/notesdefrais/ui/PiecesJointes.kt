package com.trucdecomptable.notesdefrais.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.trucdecomptable.notesdefrais.export.ExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Les pièces jointes : vignette dans les listes, visionneuse plein écran (photo
 * ou PDF page par page, avec zoom) et recadrage manuel d'une photo.
 */

private fun fichierDe(context: Context, nom: String): File =
    File(ExportManager.dossierJustificatifs(context), nom)

private fun estPdf(nom: String): Boolean = nom.endsWith(".pdf", ignoreCase = true)

/** Rendu des pages d'un PDF avec PdfRenderer : rien à ajouter aux dépendances. */
object PdfPages {

    fun nombre(fichier: File): Int = avec(fichier) { it.pageCount } ?: 0

    fun rendre(fichier: File, page: Int, largeur: Int = 1600): Bitmap? = avec(fichier) { moteur ->
        if (page < 0 || page >= moteur.pageCount) return@avec null
        moteur.openPage(page).use { p ->
            val echelle = (largeur.toFloat() / p.width.toFloat()).coerceIn(0.4f, 3f)
            val bitmap = Bitmap.createBitmap(
                (p.width * echelle).toInt().coerceAtLeast(1),
                (p.height * echelle).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            // PdfRenderer laisse les zones non peintes transparentes : sans ce
            // fond blanc, la page s'affiche blanche sur blanc.
            android.graphics.Canvas(bitmap).drawColor(AndroidColor.WHITE)
            p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    private fun <T> avec(fichier: File, bloc: (PdfRenderer) -> T): T? {
        if (!fichier.exists()) return null
        val descripteur = runCatching {
            ParcelFileDescriptor.open(fichier, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull() ?: return null
        return try {
            PdfRenderer(descripteur).use(bloc)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { descripteur.close() }
        }
    }
}

/** Décode une photo stockée en plafonnant sa taille, pour rester économe. */
fun decoderAvecPlafond(fichier: File, maxPx: Int): Bitmap? {
    if (!fichier.exists()) return null
    val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(fichier.absolutePath, bornes)
    if (bornes.outWidth <= 0 || bornes.outHeight <= 0) return null
    var facteur = 1
    while (bornes.outWidth / facteur > maxPx || bornes.outHeight / facteur > maxPx) facteur *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = facteur }
    return BitmapFactory.decodeFile(fichier.absolutePath, options)
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
 * Vignette d'une pièce. Un PDF montre désormais sa première page : c'est ce qui
 * rend une facture reçue par mail reconnaissable d'un coup d'œil.
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
            val fichier = fichierDe(context, nom)
            val bitmap = if (estPdf(nom)) PdfPages.rendre(fichier, 0, 400) else decoderAvecPlafond(fichier, 320)
            bitmap?.asImageBitmap()
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
 * Visionneuse plein écran : photo ou PDF page par page, zoom au pincement (et
 * deux boutons pour ceux qui préfèrent), recadrage d'une photo.
 *
 * [onRecadrer] reçoit le nom de la pièce et le bitmap recadré : c'est le
 * ViewModel qui décide quoi en faire, la vue ne touche jamais au disque.
 */
@Composable
fun ApercuPiece(
    nom: String,
    onFermer: () -> Unit,
    onRecadrer: ((String, Bitmap) -> Unit)? = null,
) {
    val context = LocalContext.current
    val fichier = remember(nom) { fichierDe(context, nom) }
    val pdf = estPdf(nom)

    var page by remember(nom) { mutableStateOf(0) }
    var pages by remember(nom) { mutableStateOf(1) }
    var image by remember(nom, page) { mutableStateOf<ImageBitmap?>(null) }
    var charge by remember(nom, page) { mutableStateOf(true) }
    var echelle by remember(nom) { mutableStateOf(1f) }
    var decalage by remember(nom) { mutableStateOf(Offset.Zero) }
    var recadrage by remember(nom) { mutableStateOf(false) }

    LaunchedEffect(nom, page) {
        charge = true
        val bitmap = withContext(Dispatchers.IO) {
            if (pdf) {
                pages = PdfPages.nombre(fichier).coerceAtLeast(1)
                PdfPages.rendre(fichier, page, 1900)
            } else {
                decoderAvecPlafond(fichier, 2400)
            }
        }
        image = bitmap?.asImageBitmap()
        charge = false
    }

    if (recadrage) {
        image?.let { source ->
            EditeurRecadrage(
                source = source,
                onAnnuler = { recadrage = false },
                onValider = { bitmap ->
                    onRecadrer?.invoke(nom, bitmap)
                    recadrage = false
                    onFermer()
                },
            )
            return
        }
    }

    Dialog(
        onDismissRequest = onFermer,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val apercu = image
            when {
                apercu != null -> ImageZoomable(
                    bitmap = apercu,
                    echelle = echelle,
                    decalage = decalage,
                    onGeste = { z, d -> echelle = z; decalage = d },
                    modifier = Modifier.fillMaxSize().padding(top = 60.dp, bottom = 60.dp),
                )

                charge -> Text(
                    "Chargement…",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> Text(
                    "Pièce illisible",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconeBlanche(Icons.Default.Close, "Fermer", onFermer)
                IconeBlanche(Icons.Default.Add, "Zoomer") {
                    echelle = (echelle * 1.4f).coerceAtMost(6f)
                }
                IconeBlanche(Icons.Default.Remove, "Dézoomer") {
                    echelle = (echelle / 1.4f).coerceAtLeast(1f)
                    if (echelle <= 1f) decalage = Offset.Zero
                }
                if (!pdf && onRecadrer != null) {
                    IconeBlanche(Icons.Default.Crop, "Recadrer") { recadrage = true }
                }
                Text(
                    nom,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (pdf && pages > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            enabled = page > 0,
                            onClick = { page -= 1; echelle = 1f; decalage = Offset.Zero },
                        ) { Text("◀", color = Color.White) }
                        Text("${page + 1} / $pages", color = Color.White)
                        TextButton(
                            enabled = page + 1 < pages,
                            onClick = { page += 1; echelle = 1f; decalage = Offset.Zero },
                        ) { Text("▶", color = Color.White) }
                    }
                } else {
                    Text("", color = Color.White)
                }
                TextButton(onClick = { ouvrirPiece(context, nom) }) {
                    Text("Ouvrir avec…", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun IconeBlanche(icone: ImageVector, description: String, action: () -> Unit) {
    IconButton(onClick = action) {
        Icon(icone, contentDescription = description, tint = Color.White)
    }
}

/** Image avec pincement (1x à 6x) et double tap pour revenir à l'écran. */
@Composable
private fun ImageZoomable(
    bitmap: ImageBitmap,
    echelle: Float,
    decalage: Offset,
    onGeste: (Float, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gestes = Modifier.pointerInput(bitmap) {
        detectTransformGestures { _, pan, zoom, _ ->
            val nouvelle = (echelle * zoom).coerceIn(1f, 6f)
            onGeste(nouvelle, if (nouvelle <= 1f) Offset.Zero else decalage + pan)
        }
    }
    val tap = Modifier.pointerInput(bitmap) {
        detectTapGestures(onDoubleTap = { onGeste(1f, Offset.Zero) })
    }
    Box(modifier.then(gestes).then(tap), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap,
            contentDescription = "Pièce jointe",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = echelle,
                    scaleY = echelle,
                    translationX = decalage.x,
                    translationY = decalage.y,
                ),
        )
    }
}

/**
 * Recadrage manuel : quatre poignées à glisser sur la photo, rotation par pas de
 * 90°, puis validation. Chaque poignée est un petit composant avec son propre
 * geste de glissement : pas de calcul de « poignée la plus proche » à maintenir.
 */
@Composable
private fun EditeurRecadrage(
    source: ImageBitmap,
    onAnnuler: () -> Unit,
    onValider: (Bitmap) -> Unit,
) {
    var rotation by remember { mutableStateOf(0) }
    val affichee = remember(source, rotation) { tourner(source, rotation) }
    var zone by remember(affichee) { mutableStateOf(IntSize.Zero) }
    var rect by remember(affichee) { mutableStateOf(Rect.Zero) }

    val densite = LocalDensity.current
    val poignee = 32.dp
    val rayon = with(densite) { (poignee / 2).toPx() }

    Dialog(onDismissRequest = onAnnuler, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconeBlanche(Icons.Default.Close, "Annuler", onAnnuler)
                IconeBlanche(Icons.Default.RotateRight, "Pivoter") {
                    rotation = (rotation + 90) % 360
                }
                Text(
                    "Recadrage : glisse les coins",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = {
                    val bitmap = decouper(affichee, rect, zone) ?: return@TextButton
                    onValider(bitmap)
                }) { Text("Valider", color = Color.White) }
            }

            Box(
                Modifier.fillMaxSize().padding(16.dp).onSizeChanged { taille ->
                    zone = taille
                    if (rect == Rect.Zero) {
                        rect = Rect(
                            taille.width * 0.05f,
                            taille.height * 0.05f,
                            taille.width * 0.95f,
                            taille.height * 0.95f,
                        )
                    }
                },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val echelle = minOf(
                        size.width / affichee.width.toFloat(),
                        size.height / affichee.height.toFloat(),
                    )
                    val largeur = affichee.width * echelle
                    val hauteur = affichee.height * echelle
                    val gauche = (size.width - largeur) / 2f
                    val haut = (size.height - hauteur) / 2f
                    drawImage(
                        image = affichee,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(affichee.width, affichee.height),
                        dstOffset = IntOffset(gauche.roundToInt(), haut.roundToInt()),
                        dstSize = IntSize(largeur.roundToInt(), hauteur.roundToInt()),
                    )
                    // Voile sombre hors de la zone retenue, cadre blanc dessus.
                    if (rect != Rect.Zero) {
                        drawRect(color = Color(0x77000000), topLeft = Offset.Zero, size = size)
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(rect.left, rect.top),
                            size = Size(rect.width, rect.height),
                            style = Stroke(width = 4f),
                            blendMode = BlendMode.SrcOver,
                        )
                    }
                }

                if (rect != Rect.Zero) {
                    Coin(
                        position = Offset(rect.left, rect.top),
                        taille = poignee,
                        onDeplace = { dx, dy ->
                            rect = Rect(
                                (rect.left + dx).coerceIn(0f, rect.right - 40f),
                                (rect.top + dy).coerceIn(0f, rect.bottom - 40f),
                                rect.right,
                                rect.bottom,
                            )
                        },
                    )
                    Coin(
                        position = Offset(rect.right, rect.top),
                        taille = poignee,
                        onDeplace = { dx, dy ->
                            rect = Rect(
                                rect.left,
                                (rect.top + dy).coerceIn(0f, rect.bottom - 40f),
                                (rect.right + dx).coerceIn(rect.left + 40f, zone.width.toFloat()),
                                rect.bottom,
                            )
                        },
                    )
                    Coin(
                        position = Offset(rect.left, rect.bottom),
                        taille = poignee,
                        onDeplace = { dx, dy ->
                            rect = Rect(
                                (rect.left + dx).coerceIn(0f, rect.right - 40f),
                                rect.top,
                                rect.right,
                                (rect.bottom + dy).coerceIn(rect.top + 40f, zone.height.toFloat()),
                            )
                        },
                    )
                    Coin(
                        position = Offset(rect.right, rect.bottom),
                        taille = poignee,
                        onDeplace = { dx, dy ->
                            rect = Rect(
                                rect.left,
                                rect.top,
                                (rect.right + dx).coerceIn(rect.left + 40f, zone.width.toFloat()),
                                (rect.bottom + dy).coerceIn(rect.top + 40f, zone.height.toFloat()),
                            )
                        },
                    )
                    // Déplacer la zone entière en glissant à l'intérieur du cadre.
                    Box(
                        Modifier
                            .offsetPx(rect.left + rayon, rect.top + rayon)
                            .size(
                                with(densite) { (rect.width - poignee.toPx()).coerceAtLeast(1f).toDp() },
                                with(densite) { (rect.height - poignee.toPx()).coerceAtLeast(1f).toDp() },
                            )
                            .pointerInput(zone, rect) {
                                detectDragGestures { _, delta ->
                                    val nx = (rect.left + delta.x).coerceIn(0f, zone.width - rect.width)
                                    val ny = (rect.top + delta.y).coerceIn(0f, zone.height - rect.height)
                                    rect = Rect(nx, ny, nx + rect.width, ny + rect.height)
                                }
                            },
                    )
                }
            }
        }
    }
}

/** Pastille blanche glissable, positionnée en coordonnées pixels du cadre. */
@Composable
private fun Coin(position: Offset, taille: Dp, onDeplace: (Float, Float) -> Unit) {
    val densite = LocalDensity.current
    val demi = with(densite) { (taille / 2).toPx() }
    Box(
        Modifier
            .offsetPx(position.x - demi, position.y - demi)
            .size(taille)
            .clip(CircleShape)
            .background(Color.White)
            .pointerInput(position) {
                detectDragGestures { _, delta -> onDeplace(delta.x, delta.y) }
            },
    )
}

private fun Modifier.offsetPx(x: Float, y: Float): Modifier =
    this.offset { IntOffset(x.roundToInt(), y.roundToInt()) }

private fun tourner(source: ImageBitmap, rotation: Int): ImageBitmap {
    if (rotation == 0) return source
    val matrice = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
    val bitmap = source.asAndroidBitmap()
    val tournee = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrice, true)
    return tournee.asImageBitmap()
}

/**
 * Découpe la zone choisie : [rect] et [zone] sont dans l'espace affiché
 * (ContentScale.Fit centré), on repasse aux coordonnées de l'image source.
 */
private fun decouper(source: ImageBitmap, rect: Rect, zone: IntSize): Bitmap? {
    if (zone.width == 0 || zone.height == 0 || rect.width < 8f || rect.height < 8f) return null
    val echelle = minOf(
        zone.width / source.width.toFloat(),
        zone.height / source.height.toFloat(),
    )
    val largeurAffichee = source.width * echelle
    val hauteurAffichee = source.height * echelle
    val gauche = (zone.width - largeurAffichee) / 2f
    val haut = (zone.height - hauteurAffichee) / 2f
    val x = ((rect.left - gauche) / echelle).toInt().coerceIn(0, source.width - 1)
    val y = ((rect.top - haut) / echelle).toInt().coerceIn(0, source.height - 1)
    val largeur = (rect.width / echelle).toInt().coerceIn(1, source.width - x)
    val hauteur = (rect.height / echelle).toInt().coerceIn(1, source.height - y)
    return runCatching {
        Bitmap.createBitmap(source.asAndroidBitmap(), x, y, largeur, hauteur)
    }.getOrNull()
}

/**
 * Écrit un bitmap en JPEG 90 % dans le dossier des justificatifs. Un recadrage
 * d'une grande photo peut encore dépasser 5 Mo : il est alors allégé, comme
 * toute pièce qui entre dans l'app.
 */
fun enregistrerBitmap(context: Context, bitmap: Bitmap): File {
    val cible = File(ExportManager.dossierJustificatifs(context), "${java.util.UUID.randomUUID()}.jpg")
    FileOutputStream(cible).use { sortie -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, sortie) }
    runCatching { com.trucdecomptable.notesdefrais.util.Compression.compresser(cible) }
    return cible
}
