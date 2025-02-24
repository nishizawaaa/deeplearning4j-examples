/*******************************************************************************
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *  See the NOTICE file distributed with this work for additional
 *  information regarding copyright ownership.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package org.deeplearning4j.examples.advanced.modelling.charmodelling.melodl4j;

import org.apache.commons.io.FileUtils;
import org.deeplearning4j.examples.advanced.modelling.charmodelling.utils.CharacterIterator;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.conf.BackpropType;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.LSTM;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.common.util.ArchiveUtils;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.learning.config.RmsProp;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import javax.sound.midi.InvalidMidiDataException;
import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * LSTM  Symbolic melody modelling example, to compose music from symbolic melodies extracted from MIDI.
 * LSTM logic is based closely on LSTMCharModellingExample.java.
 * See the README file in this directory for documentation.
 *
 * @author Alex Black, Donald A. Smith.
 */
public class MelodyModelingExample {
    // If you want to change the MIDI files used in learning, create a zip file containing your MIDI
    // files and replace the following path.  For example, you might use something like:
    //final static String midiFileZipFileUrlPath = "file:d:/music/midi/classical-midi.zip";
    final static String midiFileZipFileUrlPath = "http://waliberals.org/truthsite/music/bach-midi.zip";

    // For example "bach-midi.txt"
    final static String inputSymbolicMelodiesFilename = getMelodiesFileNameFromURLPath(midiFileZipFileUrlPath);

    // Examples:  bach-melodies-input.txt, beatles-melodies-input.txt ,  pop-melodies-input.txt (large)
    final static String tmpDir = System.getProperty("java.io.tmpdir");
    final static String inputSymbolicMelodiesFilePath = tmpDir + "/" + inputSymbolicMelodiesFilename;  // Point to melodies created by MidiMelodyExtractor.java
    final static String composedMelodiesOutputFilePath = tmpDir + "/composition.txt"; // You can listen to these melodies by running PlayMelodyStrings.java against this file.

    //final static String symbolicMelodiesInputFilePath = "D:/tmp/bach-melodies.txt";
    //final static String composedMelodiesOutputFilePath = tmpDir + "/bach-composition.txt"; // You can listen to these melodies by running PlayMelodyStrings.java against this file.
    final static NumberFormat numberFormat = NumberFormat.getNumberInstance();
    static {
        numberFormat.setMinimumFractionDigits(1);
        numberFormat.setMaximumFractionDigits(1);
    }
    //....
    public static void main(String[] args) throws Exception {
        String loadNetworkPath = null; //"/tmp/MelodyModel-bach.zip"; //null;
        String generationInitialization = null;        //Optional character initialization; a random character is used if null
        if (args.length == 2) {
            loadNetworkPath = args[0];
            generationInitialization = args[1];
        }

        makeMidiStringFileIfNecessary();

        int lstmLayerSize = 200;                    //Number of units in each LSTM layer
        int miniBatchSize = 32;                     //Size of mini batch to use when training
        int exampleLength = 500; //1000; 		    //Length of each training example sequence to use.
        int tbpttLength = 50;                       //Length for truncated backpropagation through time. i.e., do parameter updates ever 50 characters
        int numEpochs = 50;                            //Total number of training epochs
        int generateSamplesEveryNMinibatches = 20;  //How frequently to generate samples from the network? 1000 characters / 50 tbptt length: 20 parameter updates per minibatch
        int nSamplesToGenerate = 10;                //Number of samples to generate after each training epoch
        int nCharactersToSample = 300;                //Length of each sample to generate

        // Above is Used to 'prime' the LSTM with a character sequence to continue/complete.
        // Initialization characters must all be in CharacterIterator.getMinimalCharacterSet() by default
        Random rng = new Random(12345);
        long startTime = System.currentTimeMillis();

        System.out.println("Using " + tmpDir + " as the temporary directory");
        //Get a DataSetIterator that handles vectorization of text into something we can use to train
        // our LSTM network.
        CharacterIterator iter = getMidiIterator(miniBatchSize, exampleLength);

        if (loadNetworkPath != null) {
            MultiLayerNetwork net = MultiLayerNetwork.load(new File(loadNetworkPath), true);
            String[] samples = sampleCharactersFromNetwork(generationInitialization, net, iter, rng, nCharactersToSample, nSamplesToGenerate);
            for (String melody : samples) {
                System.out.println(melody);
                PlayMelodyStrings.playMelody(melody, 10);
                System.out.println();
            }
            System.exit(0);
        }

        int nOut = iter.totalOutcomes();

        //Set up network configuration:
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
            //.updater(new RmsProp(0.1))
            .updater(new Adam(0.005))
            .seed(System.currentTimeMillis()) // So each run generates new melodies
            .l2(0.0001)
            .weightInit(WeightInit.XAVIER)
            .list()
            .layer(0, new LSTM.Builder().nIn(iter.inputColumns()).nOut(lstmLayerSize)
                .activation(Activation.TANH).build())
            .layer(1, new LSTM.Builder().nIn(lstmLayerSize).nOut(lstmLayerSize)
                .activation(Activation.TANH).build())
//            .layer(2, new LSTM.Builder().nIn(lstmLayerSize).nOut(lstmLayerSize)
//                .activation(Activation.TANH).build())
            .layer(2, new RnnOutputLayer.Builder(LossFunctions.LossFunction.MCXENT).activation(Activation.SOFTMAX)        //MCXENT + softmax for classification
                .nIn(lstmLayerSize).nOut(nOut).build())
            .backpropType(BackpropType.TruncatedBPTT).tBPTTForwardLength(tbpttLength).tBPTTBackwardLength(tbpttLength)
            .build();

        learn(miniBatchSize, exampleLength, numEpochs, generateSamplesEveryNMinibatches, nSamplesToGenerate, nCharactersToSample, generationInitialization, rng, startTime, iter, conf);
    }

    private static void save(CharacterIterator iter) throws IOException {
        FileOutputStream fos = new FileOutputStream("/tmp/midi-character-iterator.jobj");
        ObjectOutputStream oos = new ObjectOutputStream(fos);
        oos.writeObject(iter);
        oos.close();
    }

    private static void learn(int miniBatchSize, int exampleLength, int numEpochs, int generateSamplesEveryNMinibatches, int nSamplesToGenerate, int nCharactersToSample, String generationInitialization, Random rng, long startTime, CharacterIterator iter, MultiLayerConfiguration conf) throws Exception {
        MultiLayerNetwork net = new MultiLayerNetwork(conf);
        net.init();
        //  GradientsListener listener2 = new GradientsListener(net,80);
        net.setListeners(/*listener2,*/ new ScoreIterationListener(100));

        //Print the  number of parameters in the network (and for each layer)
        Layer[] layers = net.getLayers();
        long totalNumParams = 0;
        for (int i = 0; i < layers.length; i++) {
            long nParams = layers[i].numParams();
            System.out.println("Number of parameters in layer " + i + ": " + nParams);
            totalNumParams += nParams;
        }
        System.out.println("Total number of network parameters: " + totalNumParams);

        List<String> melodies = new ArrayList<>(); // Later we print them out in reverse
        // order, so that the best melodies are at the start of the file.
        //Do training, and then generate and print samples from network
        int miniBatchNumber = 0;
        long lastTime = System.currentTimeMillis();
        for (int epoch = 0; epoch < numEpochs; epoch++) {
            System.out.println("Starting epoch " + epoch);
            while (iter.hasNext()) {
                DataSet ds = iter.next();
                net.fit(ds);
                if (++miniBatchNumber % generateSamplesEveryNMinibatches == 0) {
                    System.out.println("---------- epoch " + epoch + " --------------");
                    System.out.println("Completed " + miniBatchNumber + " minibatches of size " + miniBatchSize + "x" + exampleLength + " characters");
                    System.out.println("Sampling characters from network given initialization \"" + (generationInitialization == null ? "" : generationInitialization) + "\"");
                    String[] samples = sampleCharactersFromNetwork(generationInitialization, net, iter, rng, nCharactersToSample, nSamplesToGenerate);
                    for (int j = 0; j < samples.length; j++) {
                        System.out.println("----- Sample " + j + " ----- of epoch " + epoch);
                        System.out.println(samples[j]);
                        melodies.add(samples[j]);
                        System.out.println();
                    }
                }
                if (miniBatchNumber == 0) {
                    // save(iter); System.exit(0);
                }
            }
            iter.reset();    //Reset iterator for another epoch
            final double secondsForEpoch = 0.001 * (System.currentTimeMillis() - startTime);
            final long now = System.currentTimeMillis();
            if (melodies.size() > 0) {
                String melody = melodies.get(melodies.size() - 1);
                int seconds = 25;
                System.out.println("\nFirst " + seconds + " seconds of " + melody);
                PlayMelodyStrings.playMelody(melody, seconds);
            }
            double seconds = 0.001*(now - lastTime);
            lastTime = now;
            System.out.println("\nEpoch " + epoch + " time in seconds: " + numberFormat.format(seconds));
            // 531.9 for GPU GTX 1070
            // 821.4 for CPU i7-6700K @ 4GHZ
        }
        int indexOfLastPeriod = inputSymbolicMelodiesFilename.lastIndexOf('.');
        String saveFileName = inputSymbolicMelodiesFilename.substring(0, indexOfLastPeriod > 0 ? indexOfLastPeriod : inputSymbolicMelodiesFilename.length());
        ModelSerializer.writeModel(net, "/tmp/" + saveFileName + ".zip", false);

        // Write all melodies to the output file, in reverse order (so that the best melodies are at the start of the file).
        PrintWriter printWriter = new PrintWriter(composedMelodiesOutputFilePath);
        for (int i = melodies.size() - 1; i >= 0; i--) {
            printWriter.println(melodies.get(i));
        }
        printWriter.close();

        System.exit(0);
    }

    public static File makeSureFileIsInTmpDir(String urlString) throws IOException {
        final URL url = new URL(urlString);
        final String filename = urlString.substring(1+urlString.lastIndexOf("/"));
        final File f = new File(tmpDir + "/" + filename);
        if (f.exists()) {
            System.out.println("Using existing " + f.getAbsolutePath());
        } else {
            FileUtils.copyURLToFile(url, f);
            if (!f.exists()) {
                throw new RuntimeException(f.getAbsolutePath() + " does not exist");
            }
            System.out.println("File downloaded to " + f.getAbsolutePath());
        }
        return f;
    }

    //https://stackoverflow.com/questions/10633595/java-zip-how-to-unzip-folder
    public static void unzip(File zipFile, File targetDirFile) throws IOException {
        InputStream is =  new FileInputStream(zipFile);
        Path targetDir = targetDirFile.toPath();
        targetDir = targetDir.toAbsolutePath();
        try (ZipInputStream zipIn = new ZipInputStream(is)) {
            for (ZipEntry ze; (ze = zipIn.getNextEntry()) != null; ) {
                Path resolvedPath = targetDir.resolve(ze.getName()).normalize();
                if (!resolvedPath.startsWith(targetDir)) {
                    // see: https://snyk.io/research/zip-slip-vulnerability
                    throw new RuntimeException("Entry with an illegal path: "
                            + ze.getName());
                }
                if (ze.isDirectory()) {
                    Files.createDirectories(resolvedPath);
                } else {
                    Files.createDirectories(resolvedPath.getParent());
                    Files.copy(zipIn, resolvedPath);
                }
            }
        }
        is.close();
    }
    private static void makeMidiStringFileIfNecessary() throws IOException, InvalidMidiDataException {
        final File inputMelodiesFile = new File(inputSymbolicMelodiesFilePath);
        if (inputMelodiesFile.exists() && inputMelodiesFile.length()>1000) {
            System.out.println("Using existing " + inputSymbolicMelodiesFilePath);
            return;
        }
        final File midiZipFile = makeSureFileIsInTmpDir(midiFileZipFileUrlPath);
        final String midiZipFileName = midiZipFile.getName();
        final String midiZipFileNameWithoutSuffix = midiZipFileName.substring(0,midiZipFileName.lastIndexOf("."));
        final File outputDirectoryFile  = new File(tmpDir,midiZipFileNameWithoutSuffix);
        final String outputDirectoryPath = outputDirectoryFile.getAbsolutePath();
        if (!outputDirectoryFile.exists()) {
            outputDirectoryFile.mkdir();
        }
        if (!outputDirectoryFile.exists() || !outputDirectoryFile.isDirectory()) {
            throw new IllegalStateException(outputDirectoryFile + " is not a directory or can't be created");
        }
        final PrintStream printStream = new PrintStream(inputSymbolicMelodiesFilePath);
        System.out.println("Unzipping "+ midiZipFile.getAbsolutePath() + " to " + outputDirectoryPath);
        unzip(midiZipFile, outputDirectoryFile);
        System.out.println("Extracted " + midiZipFile.getAbsolutePath() + " to " + outputDirectoryPath);
        MidiMelodyExtractor.processDirectoryAndWriteMelodyFile(outputDirectoryFile,inputMelodiesFile);
        printStream.close();
    }
    /**
     * Sets up and return a simple DataSetIterator that does vectorization based on the melody sample.
     *
     * @param miniBatchSize  Number of text segments in each training mini-batch
     * @param sequenceLength Number of characters in each text segment.
     */
    public static CharacterIterator getMidiIterator(int miniBatchSize, int sequenceLength) throws Exception {
        final char[] validCharacters = MelodyStrings.allValidCharacters.toCharArray(); //Which characters are allowed? Others will be removed
        return new CharacterIterator(inputSymbolicMelodiesFilePath, Charset.forName("UTF-8"),
            miniBatchSize, sequenceLength, validCharacters, new Random(12345), MelodyStrings.COMMENT_STRING);
    }

    /**
     * Generate a sample from the network, given an (optional, possibly null) initialization. Initialization
     * can be used to 'prime' the RNN with a sequence you want to extend/continue.<br>
     * Note that the initalization is used for all samples
     *
     * @param initialization     String, may be null. If null, select a random character as initialization for all samples
     * @param charactersToSample Number of characters to sample from network (excluding initialization)
     * @param net                MultiLayerNetwork with one or more LSTM/RNN layers and a softmax output layer
     * @param iter               CharacterIterator. Used for going from indexes back to characters
     */
    public static String[] sampleCharactersFromNetwork(String initialization, MultiLayerNetwork net,
                                                       CharacterIterator iter, Random rng, int charactersToSample, int numSamples) {
        //Set up initialization. If no initialization: use a random character
        if (initialization == null) {
            initialization = String.valueOf(iter.getRandomCharacter());
        }

        //Create input for initialization
        INDArray initializationInput = Nd4j.zeros(numSamples, iter.inputColumns(), initialization.length());
        char[] init = initialization.toCharArray();
        for (int i = 0; i < init.length; i++) {
            int idx = iter.convertCharacterToIndex(init[i]);
            for (int j = 0; j < numSamples; j++) {
                initializationInput.putScalar(new int[]{j, idx, i}, 1.0f);
            }
        }

        StringBuilder[] sb = new StringBuilder[numSamples];
        for (int i = 0; i < numSamples; i++) sb[i] = new StringBuilder(initialization);

        //Sample from network (and feed samples back into input) one character at a time (for all samples)
        //Sampling is done in parallel here
        net.rnnClearPreviousState();
        INDArray output = net.rnnTimeStep(initializationInput);
        output = output.tensorAlongDimension((int)output.size(2) - 1, 1, 0);    //Gets the last time step output

        for (int i = 0; i < charactersToSample; i++) {
            //Set up next input (single time step) by sampling from previous output
            INDArray nextInput = Nd4j.zeros(numSamples, iter.inputColumns());
            //Output is a probability distribution. Sample from this for each example we want to generate, and add it to the new input
            for (int s = 0; s < numSamples; s++) {
                double[] outputProbDistribution = new double[iter.totalOutcomes()];
                for (int j = 0; j < outputProbDistribution.length; j++)
                    outputProbDistribution[j] = output.getDouble(s, j);
                int sampledCharacterIdx = sampleFromDistribution(outputProbDistribution, rng);

                nextInput.putScalar(new int[]{s, sampledCharacterIdx}, 1.0f);        //Prepare next time step input
                sb[s].append(iter.convertIndexToCharacter(sampledCharacterIdx));    //Add sampled character to StringBuilder (human readable output)
            }

            output = net.rnnTimeStep(nextInput);    //Do one time step of forward pass
        }

        String[] out = new String[numSamples];
        for (int i = 0; i < numSamples; i++) out[i] = sb[i].toString();
        return out;
    }

    /**
     * Given a probability distribution over discrete classes, sample from the distribution
     * and return the generated class index.
     *
     * @param distribution Probability distribution over classes. Must sum to 1.0
     */
    public static int sampleFromDistribution(double[] distribution, Random rng) {
        double d = 0.0;
        double sum = 0.0;
        for (int t = 0; t < 10; t++) {
            d = rng.nextDouble();
            sum = 0.0;
            for (int i = 0; i < distribution.length; i++) {
                sum += distribution[i];
                if (d <= sum) return i;
            }
            //If we haven't found the right index yet, maybe the sum is slightly
            //lower than 1 due to rounding error, so try again.
        }
        //Should be extremely unlikely to happen if distribution is a valid probability distribution
        throw new IllegalArgumentException("Distribution is invalid? d=" + d + ", sum=" + sum);
    }
    private static String getMelodiesFileNameFromURLPath(String midiFileZipFileUrlPath) {
        if (!(midiFileZipFileUrlPath.endsWith(".zip") || midiFileZipFileUrlPath.endsWith(".ZIP"))) {
            throw new IllegalStateException("zipFilePath must end with .zip");
        }
        midiFileZipFileUrlPath = midiFileZipFileUrlPath.replace('\\','/');
        String fileName = midiFileZipFileUrlPath.substring(midiFileZipFileUrlPath.lastIndexOf("/") + 1);
        return fileName + ".txt";
    }
}

