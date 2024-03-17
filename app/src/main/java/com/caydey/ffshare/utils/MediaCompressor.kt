package com.caydey.ffshare.utils
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformation
import com.caydey.ffshare.R
import com.caydey.ffshare.utils.logs.Log
import com.caydey.ffshare.utils.logs.LogsDbHelper
import timber.log.Timber
import java.util.*
class MediaCompressor(private val context: Context) {
    private val utils: Utils by lazy { Utils(context) }
    private val settings: Settings by lazy { Settings(context) }
    private val logsDbHelper by lazy { LogsDbHelper(context) }
    fun cancelAllOperations() {
        Timber.d("Canceling all ffmpeg operations")
        FFmpegKit.cancel()
    }
    @SuppressLint("SetTextI18n")
    fun compressSingleFile(
        activity: Activity,
        inputFileUri: Uri,
        successHandler: (uri: Uri, inputFileSize: Long, outputFileSize: Long) -> Unit,
        failureHandler: () -> Unit
    ) {
        val txtFfmpegCommand: TextView = activity.findViewById(R.id.txtFfmpegCommand)
        val txtInputFile: TextView = activity.findViewById(R.id.txtInputFile)
        val txtInputFileSize: TextView = activity.findViewById(R.id.txtInputFileSize)
        val txtOutputFile: TextView = activity.findViewById(R.id.txtOutputFile)
        val txtOutputFileSize: TextView = activity.findViewById(R.id.txtOutputFileSize)
        val txtProcessedTime: TextView = activity.findViewById(R.id.txtProcessedTime)
        val txtProcessedTimeTotal: TextView = activity.findViewById(R.id.txtProcessedTimeTotal)
        val txtProcessedPercent: TextView = activity.findViewById(R.id.txtProcessedPercent)
        val processedTableRow: TableRow = activity.findViewById(R.id.processedTableRow)
        val btnCancel: Button = activity.findViewById(R.id.btnCancel)
        btnCancel.setOnClickListener {
            Toast.makeText(context, context.getString(R.string.ffmpeg_canceled), Toast.LENGTH_LONG).show()
            cancelAllOperations()
            failureHandler()
        }
        val mediaType = utils.getMediaType(inputFileUri)
        if (!utils.supportedMediaType(mediaType)) {
            Toast.makeText(context, context.getString(R.string.error_unknown_filetype), Toast.LENGTH_LONG).show()
            failureHandler()
            return
        }
        val showProgress = !utils.isImage(mediaType)
        if (!showProgress) {
            processedTableRow.visibility = View.INVISIBLE
        }
        val inputFileName = utils.getFilenameFromUri(inputFileUri) ?: "unknown"
        val (outputFile, outputMediaType) = utils.getCacheOutputFile(inputFileUri, mediaType)
        val outputFileUri = FileProvider.getUriForFile(context, context.applicationContext.packageName+".fileprovider", outputFile)
        val mediaInformation = FFprobeKit.getMediaInformation(FFmpegKitConfig.getSafParameterForRead(context, inputFileUri)).mediaInformation
        if (mediaInformation == null) {
            Timber.d("Unable to get media information, throwing error")
            Toast.makeText(context, context.getString(R.string.error_invalid_file), Toast.LENGTH_LONG).show()
            failureHandler()
            return
        }
        val inputFileSize = mediaInformation.size.toLong()
        var duration = 0
        if (showProgress) {
            if (mediaInformation.duration == null || mediaInformation.size == null) {
                Timber.d("Unable to get size & duration for media, throwing error")
                Toast.makeText(context, context.getString(R.string.error_invalid_file), Toast.LENGTH_LONG).show()
                failureHandler()
                return
            }
            duration = (mediaInformation.duration.toFloat() * 1_000).toInt()
        }
        val params = createFFmpegParams(inputFileUri, mediaInformation, mediaType, outputMediaType)
        val inputSaf: String = FFmpegKitConfig.getSafParameterForRead(context, inputFileUri)
        val outputSaf: String = FFmpegKitConfig.getSafParameterForWrite(context, outputFileUri)
        val command = "-y -i $inputSaf $params $outputSaf"
        val prettyCommand = "ffmpeg -y -i $inputFileName $params ${outputFile.name}"
        Handler(Looper.getMainLooper()).post {
            txtFfmpegCommand.text = prettyCommand
            txtInputFile.text = inputFileName
            txtInputFileSize.text = utils.bytesToHuman(inputFileSize)
            txtOutputFile.text = outputFile.name
            txtOutputFileSize.text = utils.bytesToHuman(0)
            txtProcessedTime.text = utils.millisToMicrowave(0)
            txtProcessedTimeTotal.text = utils.millisToMicrowave(duration)
            txtProcessedPercent.text = context.getString(R.string.format_percentage, 0.0f)
        }
        Timber.d("Executing ffmpeg command: 'ffmpeg %s'", command)
        FFmpegKit.executeAsync(command, { session ->
            if (!session.returnCode.isValueSuccess) {
                if (!session.returnCode.isValueCancel) {
                    Timber.d("ffmpeg command failed")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, context.getString(R.string.ffmpeg_error), Toast.LENGTH_LONG).show()
                    }
                    logsDbHelper.addLog(Log(
                        prettyCommand,
                        inputFileName,
                        outputFile.name,
                        false,
                        session.output,
                        inputFileSize,
                        -1
                    ))
                    failureHandler()
                }
            } else {
                Timber.d("ffmpeg command executed successfully")
                if (settings.copyExifTags && ExifTools.isValidType(mediaType)) {
                    Timber.d("copying exif tags")
                    ExifTools.copyExif(context.contentResolver.openInputStream(inputFileUri)!!, outputFile)
                }
                val outputFileCurrentSize = outputFile.length()
                Handler(Looper.getMainLooper()).post {
                    txtProcessedPercent.text = context.getString(R.string.format_percentage, 100.0f)
                    txtProcessedTime.text = utils.millisToMicrowave(duration)
                    if (outputFileCurrentSize > 0) {
                        txtOutputFileSize.text = utils.bytesToHuman(outputFileCurrentSize)
                    }
                }
                logsDbHelper.addLog(Log(
                    prettyCommand,
                    inputFileName,
                    outputFile.name,
                    true,
                    session.output,
                    inputFileSize,
                    outputFileCurrentSize
                ))
                successHandler(outputFileUri, inputFileSize, outputFileCurrentSize)
            }
        }, {  }, { statistics ->
            Handler(Looper.getMainLooper()).post {
                if (showProgress) {
                    txtProcessedPercent.text = context.getString(R.string.format_percentage, (statistics.time.toFloat() / duration) * 100)
                    txtProcessedTime.text = utils.millisToMicrowave(statistics.time.toInt())
                }
                txtOutputFileSize.text = utils.bytesToHuman(statistics.size)
            }
        })
    }
    fun compressFiles(activity: Activity, inputFilesUri: ArrayList<Uri>, callback: (uris: ArrayList<Uri>) -> Unit) {
        val txtCommandNumber: TextView = activity.findViewById(R.id.txtCommandNumber)
        val inputFilesCount = inputFilesUri.size
        val compressedFiles = ArrayList<Uri>()
        var totalInputFileSize = 0L
        var totalOutputFileSize = 0L
        lateinit var iteratorFunction: (Int, Boolean) -> Unit
        iteratorFunction = fun(i, error) {
            if (i < inputFilesCount) {
                Timber.d("Processing %d of %d files", i+1, inputFilesCount)
                if (inputFilesCount > 1) {
                    Handler(Looper.getMainLooper()).post {
                        txtCommandNumber.text = context.getString(R.string.command_x_of_y, i+1, inputFilesCount)
                    }
                }
                compressSingleFile(activity, inputFilesUri[i], failureHandler = {
                    iteratorFunction(i+1, true)
                }, successHandler = { uri, inputFileSize, outputFileSize ->
                    totalInputFileSize += inputFileSize
                    totalOutputFileSize += outputFileSize
                    compressedFiles.add(uri)
                    iteratorFunction(i+1, false)
                })
            }
            if (i >= inputFilesCount || error) {
                if (settings.showStatusMessages && !error) {
                    val totalOutputFileSizeHuman = utils.bytesToHuman(totalOutputFileSize)
                    val compressionPercentage = (1 - (totalOutputFileSize.toDouble() / totalInputFileSize)) * 100
                    val toastMessage = context.getString(R.string.media_reduction_message, totalOutputFileSizeHuman, compressionPercentage)
                    Handler(Looper.getMainLooper()).post {
                        Timber.d("Showing compression size toast message")
                        Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show()
                    }
                }
                callback(compressedFiles)
            }
        }
        iteratorFunction(0, false)
    }
    private fun createFFmpegParams(inputFile: Uri, mediaInformation: MediaInformation, mediaType: Utils.MediaType, outputMediaType: Utils.MediaType): String {
        val params = StringJoiner(" ")
        params.add("-preset ${settings.compressionPreset}")
        if (utils.isVideo(outputMediaType)) {
            params.add("-crf ${settings.videoCrf}")
            params.add("-vf format=yuv420p")
            if (outputMediaType == Utils.MediaType.MP4) {
                params.add("-c:v h264")
            }
            if (settings.videoMaxFileSize != 0) {
                if (outputMediaType != Utils.MediaType.WEBM) {
                    val maxBitrate = (settings.videoMaxFileSize / mediaInformation.duration.toFloat().toInt())
                    Timber.d("Maximum bitrate for targeted filesize (%dK): %dk", settings.videoMaxFileSize, maxBitrate)
                    val audioSplit = maxBitrate / 3
                    val audioBitrate = if (audioSplit > 192) 192 else if (audioSplit > 128) 128 else if (audioSplit > 96) 96 else if (audioSplit > 64) 64 else if (audioSplit > 32) 32 else 24
                    params.add("-b:a ${audioBitrate}k")
                    val videoBitrate = (maxBitrate - audioBitrate)
                    params.add("-maxrate ${videoBitrate}k -bufsize ${videoBitrate}k")
                }
            }
        } else if (utils.isImage(outputMediaType)) {
            if (outputMediaType == Utils.MediaType.JPEG) {
                params.add("-c:v mjpeg")
            } else if (outputMediaType == Utils.MediaType.WEBP) {
                params.add("-c:v libwebp")
            }
        }
        return params.toString()
    }
}
