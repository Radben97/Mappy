package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.utils.PolylineUtils
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

class MapActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private lateinit var tvStatus: TextView
    private lateinit var etDestination: EditText

    private var mapLibreMap: MapLibreMap? = null
    private var tileServer: MbtilesServer? = null
    private val httpClient = OkHttpClient()

    private val ROUTE_SOURCE_ID = "route-source-id"
    private val ROUTE_LAYER_ID = "route-layer-id"
    private val DEST_SOURCE_ID = "dest-marker-source-id"
    private val DEST_LAYER_ID = "dest-marker-layer-id"
    private val DEST_ICON_ID = "dest-pin-icon"

    // Fallback origin: Chennai Central (13.0827, 80.2707)
    private val fallbackStart = LatLng(13.0827, 80.2707)

    // Permission launcher for runtime GPS requests
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            mapLibreMap?.getStyle { enableLocationComponent(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        tvStatus = findViewById(R.id.tvStatus)
        etDestination = findViewById(R.id.etDestination)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        // Clear active route and destination marker
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            clearRouteAndMarker()
        }

        // Center camera on current GPS location
        findViewById<Button>(R.id.btnGps).setOnClickListener {
            centerOnUserLocation()
        }

        // Handle search button click
        findViewById<Button>(R.id.btnSearch).setOnClickListener {
            triggerSearch()
        }

        // Handle keyboard "Search" action
        etDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerSearch()
                true
            } else {
                false
            }
        }

        // Start offline tile server on background thread
        Thread {
            try {
                val mbtilesFile = File(filesDir, "chennai.mbtiles")
                if (!mbtilesFile.exists() || mbtilesFile.length() == 0L) {
                    assets.open("chennai.mbtiles").use { input ->
                        FileOutputStream(mbtilesFile).use { output -> input.copyTo(output) }
                    }
                }

                val server = MbtilesServer(mbtilesFile)
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                tileServer = server

                runOnUiThread {
                    mapView.getMapAsync { map ->
                        mapLibreMap = map

                        map.cameraPosition = CameraPosition.Builder()
                            .target(fallbackStart)
                            .zoom(12.5)
                            .build()

                        // Load vector style directly from JSON string
                        map.setStyle(Style.Builder().fromJson(server.styleJson)) { style ->
                            initLayers(style)
                            checkLocationPermissionsAndEnable(style)
                        }

                        // Map click listener: drop marker and calculate route
                        map.addOnMapClickListener { targetLatLng ->
                            hideKeyboard()
                            etDestination.setText("%.4f, %.4f".format(targetLatLng.latitude, targetLatLng.longitude))
                            setDestinationAndRoute(targetLatLng, "Tapped Location")
                            true
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Map load error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun triggerSearch() {
        hideKeyboard()
        val query = etDestination.text.toString().trim()
        if (query.isNotEmpty()) {
            geocodeAndRoute(query)
        } else {
            Toast.makeText(this, "Enter a destination name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkLocationPermissionsAndEnable(style: Style) {
        val fineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineLocation) {
            enableLocationComponent(style)
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        val locationComponent = mapLibreMap?.locationComponent ?: return
        val activationOptions = LocationComponentActivationOptions.builder(this, style).build()
        locationComponent.activateLocationComponent(activationOptions)
        locationComponent.isLocationComponentEnabled = true
        locationComponent.cameraMode = CameraMode.NONE
        locationComponent.renderMode = RenderMode.COMPASS
    }

    private fun centerOnUserLocation() {
        val lastLocation = mapLibreMap?.locationComponent?.lastKnownLocation
        if (lastLocation != null) {
            val userLatLng = LatLng(lastLocation.latitude, lastLocation.longitude)
            mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 14.5))
        } else {
            Toast.makeText(this, "Awaiting GPS fix...", Toast.LENGTH_SHORT).show()
            mapLibreMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(fallbackStart, 13.0))
        }
    }

    private fun initLayers(style: Style) {
        // 1. Route Polyline Layer
        if (style.getSource(ROUTE_SOURCE_ID) == null) {
            val emptyRoute = Feature.fromGeometry(LineString.fromLngLats(emptyList()))
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, emptyRoute))

            val lineLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).apply {
                setProperties(
                    lineColor("#1E88E5"),
                    lineWidth(5.5f),
                    lineCap("round"),
                    lineJoin("round")
                )
            }
            style.addLayer(lineLayer)
        }

        // 2. Destination Marker Layer
        if (style.getSource(DEST_SOURCE_ID) == null) {
            style.addImage(DEST_ICON_ID, createPinBitmap())
            style.addSource(GeoJsonSource(DEST_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))

            val markerLayer = SymbolLayer(DEST_LAYER_ID, DEST_SOURCE_ID).apply {
                setProperties(
                    iconImage(DEST_ICON_ID),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconAnchor("bottom")
                )
            }
            style.addLayer(markerLayer)
        }
    }

    private fun setDestinationAndRoute(destination: LatLng, label: String) {
        // Render destination pin
        mapLibreMap?.getStyle { style ->
            val destSource = style.getSourceAs<GeoJsonSource>(DEST_SOURCE_ID)
            val pointFeature = Feature.fromGeometry(
                Point.fromLngLat(destination.longitude, destination.latitude)
            )
            destSource?.setGeoJson(FeatureCollection.fromFeatures(arrayOf(pointFeature)))
        }

        // Get starting location (live GPS puck or fallback)
        val lastLocation = mapLibreMap?.locationComponent?.lastKnownLocation
        val origin = if (lastLocation != null) {
            LatLng(lastLocation.latitude, lastLocation.longitude)
        } else {
            fallbackStart
        }

        tvStatus.text = "Routing to $label..."
        fetchGraphHopperRoute(origin, destination)
    }

    private fun geocodeAndRoute(placeName: String) {
        tvStatus.text = "Searching for \"$placeName\"..."
        Thread {
            try {
                val origin = mapLibreMap?.locationComponent?.lastKnownLocation?.let {
                    LatLng(it.latitude, it.longitude)
                } ?: fallbackStart

                // Photon Geocoder (OSM data, free, biased to Chennai)
                val encodedQuery = URLEncoder.encode(placeName, "UTF-8")
                val geocodeUrl = "https://photon.komoot.io/api/?q=$encodedQuery&lat=${origin.latitude}&lon=${origin.longitude}&limit=1"

                val request = Request.Builder().url(geocodeUrl).build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: return@Thread

                val json = JSONObject(body)
                val features = json.getJSONArray("features")

                if (features.length() == 0) {
                    runOnUiThread {
                        tvStatus.text = "Location not found"
                        Toast.makeText(this, "Could not find \"$placeName\"", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                val feature = features.getJSONObject(0)
                val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
                val foundLng = coords.getDouble(0)
                val foundLat = coords.getDouble(1)
                val foundName = feature.optJSONObject("properties")?.optString("name") ?: placeName

                runOnUiThread {
                    val destCoord = LatLng(foundLat, foundLng)
                    setDestinationAndRoute(destCoord, foundName)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Search error"
                    Toast.makeText(this, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun fetchGraphHopperRoute(from: LatLng, to: LatLng) {
        Thread {
            try {
                // Replace with your valid API key from graphhopper.com
                val apiKey = Secret.GRAPHHOPPER_API_KEY

                val url = "https://graphhopper.com/api/1/route?" +
                        "point=${from.latitude},${from.longitude}" +
                        "&point=${to.latitude},${to.longitude}" +
                        "&profile=car" +
                        "&points_encoded=true" +
                        "&key=$apiKey"

                android.util.Log.d("GraphHopper", "Request: $url")

                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                android.util.Log.d("GraphHopper", "Code: ${response.code}, Response: $body")

                val json = JSONObject(body)

                // GraphHopper returns {"message": "..."} when a request fails
                if (json.has("message")) {
                    val errorMsg = json.getString("message")
                    runOnUiThread {
                        tvStatus.text = "API Error: $errorMsg"
                        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val paths = json.optJSONArray("paths")
                if (paths == null || paths.length() == 0) {
                    runOnUiThread {
                        tvStatus.text = "No route found between points"
                    }
                    return@Thread
                }

                val routeObj = paths.getJSONObject(0)
                val encodedPoints = routeObj.getString("points")
                val distanceKm = routeObj.getDouble("distance") / 1000.0
                val timeMinutes = (routeObj.getDouble("time") / 1000.0 / 60.0).toInt()

                val points = PolylineUtils.decode(encodedPoints, 5)
                val lineString = LineString.fromLngLats(points)

                runOnUiThread {
                    tvStatus.text = "%.1f km · %d min".format(distanceKm, timeMinutes)

                    mapLibreMap?.getStyle { style ->
                        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
                        source?.setGeoJson(lineString)

                        if (points.isNotEmpty()) {
                            val boundsBuilder = LatLngBounds.Builder()
                            points.forEach { pt ->
                                boundsBuilder.include(LatLng(pt.latitude(), pt.longitude()))
                            }
                            mapLibreMap?.animateCamera(
                                CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 150)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GraphHopper", "Network error", e)
                runOnUiThread {
                    tvStatus.text = "Network/Routing error: ${e.message}"
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun clearRouteAndMarker() {
        etDestination.setText("")
        tvStatus.text = "Tap map or search to route"

        mapLibreMap?.getStyle { style ->
            val routeSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
            routeSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(emptyList())))

            val destSource = style.getSourceAs<GeoJsonSource>(DEST_SOURCE_ID)
            destSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun createPinBitmap(): Bitmap {
        val size = 72
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Red circular head
        paint.color = Color.parseColor("#E53935")
        canvas.drawCircle(size / 2f, size / 3.2f, size / 3.5f, paint)

        // Needle tip pointing to ground
        val path = Path().apply {
            moveTo(size / 4f, size / 2.6f)
            lineTo(size * 3 / 4f, size / 2.6f)
            lineTo(size / 2f, size.toFloat())
            close()
        }
        canvas.drawPath(path, paint)

        // White inner circle
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 3.2f, size / 9f, paint)

        return bitmap
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() {
        mapView.onDestroy()
        tileServer?.stop()
        super.onDestroy()
    }
}

private class MbtilesServer(
    mbtilesFile: File
) : NanoHTTPD("127.0.0.1", 8080) {

    private val database = SQLiteDatabase.openDatabase(
        mbtilesFile.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY
    )

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri

        if (path == "/style.json") {
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                styleJson
            )
        }

        val match = Regex(
            "^/tiles/(\\d+)/(\\d+)/(\\d+)\\.pbf$"
        ).matchEntire(path) ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "text/plain",
            "Unknown endpoint"
        )

        val z = match.groupValues[1].toInt()
        val x = match.groupValues[2].toInt()
        val y = match.groupValues[3].toInt()

        if (z !in 0..22) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                "Invalid zoom"
            )
        }

        val tileCount = 1L shl z

        if (x < 0 || x >= tileCount || y < 0 || y >= tileCount) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                "Invalid tile coordinates"
            )
        }

        // MBTiles stores rows in TMS schema; MapLibre requests standard XYZ
        val tmsY = tileCount - 1L - y

        val cursor = database.rawQuery(
            """
            SELECT tile_data
            FROM tiles
            WHERE zoom_level = ?
              AND tile_column = ?
              AND tile_row = ?
            """.trimIndent(),
            arrayOf(z.toString(), x.toString(), tmsY.toString())
        )

        cursor.use {
            if (!it.moveToFirst()) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "Tile not found"
                )
            }

            val bytes = it.getBlob(0)

            val response = newFixedLengthResponse(
                Response.Status.OK,
                "application/x-protobuf",
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )

            response.addHeader("Content-Encoding", "gzip")
            response.addHeader("Access-Control-Allow-Origin", "*")

            return response
        }
    }

    override fun stop() {
        super.stop()
        if (database.isOpen) {
            database.close()
        }
    }

    val styleJson = """
{
  "version": 8,
  "name": "Chennai Offline",
  "glyphs": "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf",
  "sources": {
    "chennai": {
      "type": "vector",
      "tiles": [
        "http://127.0.0.1:8080/tiles/{z}/{x}/{y}.pbf"
      ],
      "minzoom": 0,
      "maxzoom": 14
    }
  },
  "layers": [
    {
      "id": "background",
      "type": "background",
      "paint": { "background-color": "#f3f0e8" }
    },
    {
      "id": "water",
      "type": "fill",
      "source": "chennai",
      "source-layer": "water",
      "paint": { "fill-color": "#9dcced" }
    },
    {
      "id": "landcover",
      "type": "fill",
      "source": "chennai",
      "source-layer": "landcover",
      "paint": { "fill-color": "#dce8ce" }
    },
    {
      "id": "landuse",
      "type": "fill",
      "source": "chennai",
      "source-layer": "landuse",
      "paint": { "fill-color": "#e9e4d8" }
    },
    {
      "id": "roads",
      "type": "line",
      "source": "chennai",
      "source-layer": "transportation",
      "paint": {
        "line-color": "#ffffff",
        "line-width": [
          "interpolate",
          ["linear"],
          ["zoom"],
          5, 0.5,
          10, 1.5,
          14, 4
        ]
      }
    },
    {
      "id": "buildings",
      "type": "fill",
      "source": "chennai",
      "source-layer": "building",
      "minzoom": 13,
      "paint": { "fill-color": "#c9c3b9" }
    },
    {
      "id": "road_labels",
      "type": "symbol",
      "source": "chennai",
      "source-layer": "transportation_name",
      "layout": {
        "symbol-placement": "line",
        "text-field": "{name}",
        "text-font": ["Noto Sans Regular"],
        "text-size": 11
      },
      "paint": {
        "text-color": "#444444",
        "text-halo-color": "#ffffff",
        "text-halo-width": 1.5
      }
    },
    {
      "id": "place_labels",
      "type": "symbol",
      "source": "chennai",
      "source-layer": "place",
      "layout": {
        "text-field": "{name}",
        "text-font": ["Noto Sans Bold"],
        "text-size": 13
      },
      "paint": {
        "text-color": "#222222",
        "text-halo-color": "#ffffff",
        "text-halo-width": 2
      }
    }
  ]
}
""".trimIndent()
}