package ir.mrahimy.conceal.ui.home

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.github.squti.androidwaverecorder.WaveRecorder
import ir.mrahimy.conceal.R
import ir.mrahimy.conceal.base.BaseAndroidViewModel
import ir.mrahimy.conceal.data.Recording
import ir.mrahimy.conceal.data.Waver
import ir.mrahimy.conceal.data.capsules.*
import ir.mrahimy.conceal.data.mapToRgbValue
import ir.mrahimy.conceal.data.mapToUniformDouble
import ir.mrahimy.conceal.util.*
import ir.mrahimy.conceal.util.ktx.getNameFromPath
import ir.mrahimy.conceal.util.ktx.getPathJava
import ir.mrahimy.conceal.util.ktx.getRgbArray
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

class MainActivityViewModel(
    application: Application,
    private val model: MainActivityModel
) : BaseAndroidViewModel(application, model) {

    private lateinit var waveRecorder: WaveRecorder

    private val _onAddingMaxAmplitude = MutableLiveData<Event<Int>>()
    val onAddingMaxAmplitude: LiveData<Event<Int>>
        get() = _onAddingMaxAmplitude

    private val _recordingFilePath = MutableLiveData<String>()

    private val _isRecording = MutableLiveData<Boolean>(false)
    val isRecording: LiveData<Boolean>
        get() = _isRecording

    val recordings = model.getAllRecordings()
    val recordingsListText = recordings.map {
        if (it.isNullOrEmpty()) R.string.empty_recording_list else R.string.empty
    }

    private val _onStartRecording = MutableLiveData<StatelessEvent>()
    val onStartRecording: LiveData<StatelessEvent>
        get() = _onStartRecording

    private val inputImagePath = MutableLiveData<String>(null)
    private val _inputImage = MutableLiveData<Bitmap>(null)
    val inputImage: LiveData<Bitmap>
        get() = _inputImage

    val isInputHintVisible = _inputImage.map { it == null }

    private val inputWavePath = MutableLiveData<String>(null)
    private val _inputWave = inputWavePath.map {
        if (it == null) null else File(it)
    }

    private val _recordTooltip = MutableLiveData<Int>(null)
    val recordTooltip: LiveData<Int>
        get() = _recordTooltip

    private val _inputImageSelectionTooltip = MutableLiveData<Int>(null)
    val inputImageSelectionTooltip: LiveData<Int>
        get() = _inputImageSelectionTooltip

    private val _inputWaveSelectionTooltip = MutableLiveData<Int>(null)
    val inputWaveSelectionTooltip: LiveData<Int>
        get() = _inputWaveSelectionTooltip

    private val _outputImageLabel = MutableLiveData<Int>(R.string.choose_input_image)
    val outputImageLabel: LiveData<Int>
        get() = _outputImageLabel

    private val _waveFileLabel =
        MutableLiveData<String>(getApplication().getString(R.string.click_to_open_file))
    val waveFileLabel: LiveData<String>
        get() = _waveFileLabel

    private val _snackMessage = MutableLiveData<Event<Int>>()
    val snackMessage: LiveData<Event<Int>>
        get() = _snackMessage

    val isOutputHintVisible =
        combine(_inputImage, _inputWave) { inputImage, inputWave ->
            if (inputImage == null) {
                _outputImageLabel.postValue(R.string.choose_input_image)
                return@combine true
            }

            if (inputWave == null) {
                _outputImageLabel.postValue(R.string.choose_wave_file_label)
                return@combine true
            }

            return@combine false
        }

    private val _waveInfo = _inputWave.map {
        if (it == null) return@map null
        WavUtil.fromWaveData(
            Wave.WavFile.openWavFile(it)
        )
    }

    val handle = combine(_inputImage, _waveInfo) { _image, _waveFile ->
        val image = _image ?: return@combine null
        val waveFile = _waveFile ?: return@combine null
        putWaveFileIntoImage(image, waveFile)
        return@combine 1
    }

    private val _isInputImageLoading = MutableLiveData<Boolean>(false)
    val isInputImageLoading: LiveData<Boolean>
        get() = _isInputImageLoading

    private val _isInputWaveLoading = MutableLiveData<Boolean>(false)
    val isInputWaveLoading: LiveData<Boolean>
        get() = _isInputWaveLoading

    private val _concealPercentage = MutableLiveData<ConcealPercentage>()
    val concealPercentage: LiveData<ConcealPercentage>
        get() = _concealPercentage

    val percentInt = _concealPercentage.map { it.percent.toInt() }

    val isPercentageVisible =
        combine(isOutputHintVisible, _concealPercentage) { outputHint, percentageData ->
            return@combine outputHint == false && percentageData?.done == false
        } as MutableLiveData

    val isDoneMarkVisible =
        combine(isOutputHintVisible, _concealPercentage) { outputHint, percentageData ->
            return@combine outputHint == false && percentageData?.done == true
        }

    private val _onStartRgbListPutAll = MutableLiveData<Event<ConcealInputData>>()
    val onStartRgbListPutAll: LiveData<Event<ConcealInputData>>
        get() = _onStartRgbListPutAll

    private lateinit var concealJob: Job
    fun cancelConcealJob() {
        concealJob.cancel()
        _concealPercentage.postValue(
            _concealPercentage.value?.copy(percent = 0f, data = null, done = false)
        )
        viewModelScope.launch {
            delay(50)
            isPercentageVisible.postValue(false)
        }
    }

    private fun putWaveFileIntoImage(
        image: Bitmap,
        waveFile: Waver
    ) {
        val rgbList = image.getRgbArray().remove3Lsb()
        val audioDataAsRgbList = waveFile.data.mapToUniformDouble().mapToRgbValue()
        val position = rgbList.putSampleRate(waveFile.sampleRate.toInt())

        concealJob = Job()
        _onStartRgbListPutAll.postValue(
            Event(ConcealInputData(rgbList, position, audioDataAsRgbList, image, concealJob))
        )
    }

    init {
        viewModelScope.launch {
            delay(1000)
            _inputImageSelectionTooltip.postValue(R.string.select_image_tooltip)
        }
    }

    /**
     * calls an event to get permission and then the view calls [startRecordingWave]
     */
    fun startRecording() {
        _recordTooltip.postValue(null)

        _concealPercentage.value?.apply {
            if (!done && percent > 0f) {
                _snackMessage.postValue(Event(R.string.please_cancel_first))
                return
            }
        }

        _onStartRecording.postValue(StatelessEvent())
    }

    fun startRecordingWave() {
        val date = Date()
        val isRecording = _isRecording.value ?: false
        if (isRecording) {
            waveRecorder.stopRecording()
            _isRecording.postValue(false)
            val filePath = _recordingFilePath.value ?: return
            selectAudioFile(filePath)
            return
        }

        val filePath =
            getApplication().applicationContext.externalCacheDir?.absolutePath +
                    "/rec_${date.time}.wav"
        _recordingFilePath.postValue(filePath)
        waveRecorder = WaveRecorder(filePath)
        waveRecorder.startRecording()
        waveRecorder.onAmplitudeListener = {
            Log.d("onAmplitudeListener", it.toString())
            if (it != 0) _onAddingMaxAmplitude.postValue(Event(it))
        }
        _isRecording.postValue(true)
    }

    fun updatePercentage(concealPercentage: ConcealPercentage) {
        _concealPercentage.postValue(concealPercentage)
        if (concealPercentage.done) {
            concealPercentage.data?.let { outputBitmap ->
                viewModelScope.launch {
                    getApplication().applicationContext.externalCacheDir?.absolutePath?.let {

                        val inputImagePath = inputImagePath.value ?: return@launch
                        val inputWavePath = inputWavePath.value ?: return@launch
                        val waver = _waveInfo.value ?: return@launch
                        val imageName = inputImagePath.getNameFromPath()
                        val bitmapInfo = SaveBitmapInfoCapsule(
                            "${imageName}_conceal",
                            Date(),
                            outputBitmap,
                            Bitmap.CompressFormat.PNG
                        )
                        val outputImagePath = bitmapInfo.save(it)

                        val list = outputBitmap.getRgbArray()
                        val parsedSampleRate = list.getSampleRate()
                        val parsedWaveData =
                            list.getAllSignedIntegers(parsedSampleRate.second)
                                .map { n -> n.toLong() }.toLongArray()
                        val wavInfo = SaveWaveInfoCapsule(
                            "parsed_from_$imageName",
                            Date(),
                            Waver(
                                parsedWaveData,
                                parsedSampleRate.first.toLong(),
                                waver.channelCount,
                                waver.frameCount,
                                waver.validBits
                            )
                        )
                        val parsedWavePath = wavInfo.save(it)
                        viewModelScope.launch {
                            model.addRecording(
                                Recording(
                                    0L,
                                    inputImagePath,
                                    outputImagePath,
                                    inputWavePath,
                                    parsedWavePath,
                                    Date().time
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun showSlide(position: Int) {
        val input = _inputImage.value ?: return
//        val output = outputBitmap.value ?: return
//        val bitmapArray = arrayOf(input, output)
        //TODO: navigate to slide show activity with bitmapArray & index position
    }

    private val _onChooseImage = MutableLiveData<StatelessEvent>()
    val onChooseImage: LiveData<StatelessEvent>
        get() = _onChooseImage

    fun chooseImage() {
        _concealPercentage.value?.apply {
            if (!done && percent > 0f) {
                _snackMessage.postValue(Event(R.string.please_cancel_first))
                return
            }
        }
        _onChooseImage.postValue(StatelessEvent())
    }

    private val _onChooseAudio = MutableLiveData<StatelessEvent>()
    val onChooseAudio: LiveData<StatelessEvent>
        get() = _onChooseAudio

    fun chooseAudio() {
        _concealPercentage.value?.apply {
            if (!done && percent > 0f) {
                _snackMessage.postValue(Event(R.string.please_cancel_first))
                return
            }
        }
        _onChooseAudio.postValue(StatelessEvent())
    }

    fun selectImageFile(data: Intent?) {
        viewModelScope.launch {
            _isInputImageLoading.postValue(true)
            delay(20)
            data?.data?.let {
                delay(20)
                val file = it.getPathJava(getApplication().applicationContext)
                delay(20)
                inputImagePath.postValue(file)
                _inputImage.postValue(rescaleImage(file, 400, 400))
                _isInputImageLoading.postValue(false)
            }

            if (_inputWave.value == null) {
                _inputWaveSelectionTooltip.postValue(R.string.select_audio_file_tooltip)
                if (_isRecording.value == false) {
                    _recordTooltip.postValue(R.string.hold_to_start_recording_tooltip)
                }
            }
        }
    }

    fun selectAudioFile(data: Intent?) {
        viewModelScope.launch {
            _isInputWaveLoading.postValue(true)
            delay(20)
            selectAudioFile(data?.data?.getPathJava(getApplication().applicationContext))
        }
    }

    private fun selectAudioFile(path: String?) {
        viewModelScope.launch {
            _isInputWaveLoading.postValue(true)
            delay(20)
            path?.let {
                _isInputWaveLoading.postValue(false)
                _waveFileLabel.postValue(it)
                delay(20)
                inputWavePath.postValue(it)
            }
        }
    }
}
