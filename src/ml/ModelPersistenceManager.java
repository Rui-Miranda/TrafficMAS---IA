package ml;

import java.io.*;

/**
 * Gestor de Persistência e Desserialização de Modelos de Machine Learning.
 * Garante que modelos treinados podem ser gravados em disco e recarregados
 * em qualquer execução Java sem necessidade de retreino.
 */
public class ModelPersistenceManager {

    public static final String DEFAULT_MODEL_PATH = "data/traffic_model.bin";

    /**
     * Serializa e grava o bundle do modelo no caminho especificado.
     */
    public static void saveModel(TrafficModelBundle bundle, String filePath) throws IOException {
        if (bundle == null) {
            throw new IllegalArgumentException("Bundle do modelo não pode ser nulo.");
        }
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("Caminho do ficheiro de modelo inválido.");
        }

        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            oos.writeObject(bundle);
            oos.flush();
        }
    }

    /**
     * Carrega e desserializa o bundle do modelo a partir do disco.
     */
    public static TrafficModelBundle loadModel(String filePath) throws IOException, ClassNotFoundException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("Caminho do ficheiro de modelo inválido.");
        }

        File file = new File(filePath);
        if (!file.exists()) {
            throw new FileNotFoundException("Ficheiro de modelo não encontrado: " + filePath);
        }
        if (file.length() == 0) {
            throw new IOException("Ficheiro de modelo corrompido ou vazio (0 bytes): " + filePath);
        }

        try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            Object obj = ois.readObject();
            if (!(obj instanceof TrafficModelBundle)) {
                throw new IOException("Conteúdo do ficheiro não é um TrafficModelBundle válido: " + obj.getClass().getName());
            }
            return (TrafficModelBundle) obj;
        }
    }
}
