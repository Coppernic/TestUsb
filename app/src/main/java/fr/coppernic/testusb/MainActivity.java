package fr.coppernic.testusb;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;

import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.HashMap;

import timber.log.Timber;

public class MainActivity extends AppCompatActivity {
    private static final String SCR_PERMISSION = "com.id2mp.permissions.SCR";
    private static final int REQUEST_CODE_ASK_PERMISSIONS = 123;
    private static final int USB_GET_DESCRIPTOR = 0x06;
    private static final int USB_DESCRIPTOR_TYPE_STRING = 0x0300;
    private static final byte PC_TO_RDR_GET_SLOT_STATUS = 0x65;
    private static final int USB_ENDPOINT_XFER_BULK = 2;
    private static final int USB_TIMEOUT = 10000;

    private UsbManager usbManager;
    private UsbEndpoint endpointOut;
    private UsbEndpoint endpointIn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                listReaders();
                Snackbar.make(view, "List readers", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        Timber.plant(new Timber.DebugTree());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart() {
        super.onStart();
        initPermission();
    }

    // Permission management
    private void initPermission() {
        int hasWriteContactsPermission = ActivityCompat.checkSelfPermission(this, SCR_PERMISSION);
        if (hasWriteContactsPermission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] {SCR_PERMISSION},
                    REQUEST_CODE_ASK_PERMISSIONS);
            return;
        } else {
            Timber.d("has already permission com.id2mp.permissions.SCR");
        }
    }

    private void listReaders() {
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        // First checks if any USB device is available on the device
        HashMap<String, UsbDevice> usbDeviceList = usbManager.getDeviceList();

        // For each USB device present on the terminal
        for (UsbDevice usbDevice : usbDeviceList.values()) {
            // Checks if USB permission has been granted to the peripheral
            if (usbManager.hasPermission(usbDevice)) {
                // Checks if the USB peripheral is a PC/SC reader
                if (isPcscInterface(this, usbDevice.getInterface(0))) {
                    UsbInterface usbInterface = usbDevice.getInterface(0);
                    UsbDeviceConnection usbDeviceConnection = usbManager.openDevice(usbDevice);
                    byte[] rawDescriptors = usbDeviceConnection.getRawDescriptors();

                    // Gets the indexes of the manufacturer, the product and the serial number in the USB device descriptor.
                    // Refers to USB_3_1_r1.0.pdf page 9-38
                    byte indexManufacturer = rawDescriptors[14];
                    byte indexProduct = rawDescriptors[15];
                    byte indexSerialNumber = rawDescriptors[16];

                    String manufacturer = "";
                    String product = "";
                    String serialNumber = "";

                    try {
                        manufacturer = getStringDescriptor(this, usbDeviceConnection, indexManufacturer);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    try {
                        product = getStringDescriptor(this, usbDeviceConnection, indexProduct);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    try {
                        serialNumber = getStringDescriptor(this, usbDeviceConnection, indexSerialNumber);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    Timber.e("Manufacturer: %s\nProduct: %s\nS/N: %s", manufacturer, product, serialNumber);

                    setEndpoints(this, usbInterface);
                    // GET SLOT STATUS
                    byte[] command = getSlotStatus((byte)0x00, (byte)0x00);
                    // Sends command to reader
                    int bytesWritten = usbDeviceConnection.bulkTransfer(endpointOut, command , command.length, USB_TIMEOUT);

                    if (bytesWritten < 0) Timber.e("ERROR");
                }
            }
        }
    }

    /**
     * Returns wether or not the USB device is a PC/SC reader
     * @param context A valid context
     * @param usbInterface USB interface to check
     * @return True/False
     */
    private boolean isPcscInterface (Context context, UsbInterface usbInterface) {
        int usbInterfaceClass = usbInterface.getInterfaceClass();
        int usbInterfaceSubclass = usbInterface.getInterfaceSubclass();
        return  usbInterfaceClass == UsbConstants.USB_CLASS_CSCID && usbInterfaceSubclass == 0x00;
    }

    private String getStringDescriptor (Context context, UsbDeviceConnection usbDeviceConnection, int index) throws IOException {
        byte[] stringDescriptor = new byte[255];
        int readBytes = usbDeviceConnection.controlTransfer(
                UsbConstants.USB_DIR_IN,
                USB_GET_DESCRIPTOR,
                USB_DESCRIPTOR_TYPE_STRING|index,
                1033, stringDescriptor,
                255,
                5000);

        if (readBytes < 0) {
            throw new IOException("Invalid Command");
        }

        stringDescriptor = Arrays.copyOfRange(stringDescriptor, 2, readBytes);

        return new String(stringDescriptor, "UTF-16LE");
    }

    public static byte[] getSlotStatus(byte slot, byte seq) {
        byte[] command = new byte [10];

        command[0] = PC_TO_RDR_GET_SLOT_STATUS;
        command[1] = 0x00;
        command[2] = 0x00;
        command[3] = 0x00;
        command[4] = 0x00;
        command[5] = slot;
        command[6] = seq;
        command[7] = 0x00;
        command[8] = 0x00;
        command[9] = 0x00;

        return command;
    }

    /**
     * Set endpoints for a given USB interface
     * @param context A valid context
     * @param usbInterface USB interface
     * @return True/false
     */
    // TODO return a RESULT instead of a boolean
    private boolean setEndpoints (Context context, UsbInterface usbInterface) {
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint ep = usbInterface.getEndpoint(i);
            if (ep.getType() == USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == 0) {
                    endpointOut = ep;
                }
                else {
                    endpointIn = ep;
                }
            }
        }
        if ((endpointOut == null) || (endpointIn == null)) {
            return false;
        }

        return true;
    }
}
