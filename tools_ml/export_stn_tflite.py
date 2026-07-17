import tensorflow as tf
import numpy as np
import cv2
import os

IMG = 224

def random_warp(img):
    h, w = img.shape[:2]
    src = np.float32([[0,0],[w-1,0],[w-1,h-1],[0,h-1]])
    jitter = np.random.uniform(-0.10, 0.10, size=(4,2)).astype(np.float32)
    dst = src + jitter * np.array([[w,h]], dtype=np.float32)
    M = cv2.getPerspectiveTransform(src, dst)
    warped = cv2.warpPerspective(img, M, (w,h))
    return warped

def make_batch(bs=8):
    clean = []
    warped = []
    for _ in range(bs):
        base = np.zeros((IMG,IMG,3), dtype=np.uint8)
        # draw random rectangles/text-like patterns
        for k in range(25):
            x1,y1 = np.random.randint(0,IMG-20), np.random.randint(0,IMG-20)
            x2,y2 = x1+np.random.randint(5,60), y1+np.random.randint(5,20)
            cv2.rectangle(base, (x1,y1), (min(x2,IMG-1), min(y2,IMG-1)),
                          (np.random.randint(80,255),)*3, -1)
        c = base
        wimg = random_warp(c)
        clean.append(c)
        warped.append(wimg)
    clean = np.array(clean, dtype=np.float32) / 255.0
    warped = np.array(warped, dtype=np.float32) / 255.0
    return warped, clean

def build_model():
    inp = tf.keras.Input(shape=(IMG, IMG, 3), dtype=tf.float32)
    x = inp
    # tiny encoder-decoder
    for ch in [16, 32, 64]:
        x = tf.keras.layers.Conv2D(ch, 3, padding="same", activation="relu")(x)
        x = tf.keras.layers.MaxPool2D()(x)
    for ch in [64, 32, 16]:
        x = tf.keras.layers.UpSampling2D()(x)
        x = tf.keras.layers.Conv2D(ch, 3, padding="same", activation="relu")(x)
    out = tf.keras.layers.Conv2D(3, 1, padding="same", activation="sigmoid")(x)
    return tf.keras.Model(inp, out)

def main():
    model = build_model()
    model.compile(optimizer=tf.keras.optimizers.Adam(1e-3), loss="mae")

    # quick train
    for step in range(200):
        x, y = make_batch(bs=8)
        loss = model.train_on_batch(x, y)
        if step % 20 == 0:
            print("step", step, "loss", float(loss))

    # export tflite
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = []
    tflite = converter.convert()

    os.makedirs("out", exist_ok=True)
    with open("out/stn_rectify.tflite", "wb") as f:
        f.write(tflite)
    print("Saved:", "out/stn_rectify.tflite")

if __name__ == "__main__":
    main()
