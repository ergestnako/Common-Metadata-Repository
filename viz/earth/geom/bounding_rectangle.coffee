class window.BoundingRectangle extends Module
  @include GoogleEarthEventEmitter

  # A very light blue that is very transparent
  @DEFAULT_COLOR = '55CEB0A5'

  constructor: (@west, @north, @east, @south, options={}) ->
    super()
    @id = options.id
    # multiple placemarks are used so we can handle crossing antimeridian
    @boxPlacemarks = []
    @color = BoundingRectangle.DEFAULT_COLOR
    @color = options.color if options.color

    # Make a zero height bounding rectangle slightly taller so it's visible
    if @north == @south
      if @north == 90
        @south = @south - 0.01
      else
        @north = @north + 0.01

  @fromObject: (data)->
    new BoundingRectangle(data.west, data.north, data.east, data.south)

  # Adds the ring to google earth
  display: (ge) ->
    if this.crossesAntimeridan()
      @boxPlacemarks.push(this.createBoxPolygon(ge, @west, 180))
      @boxPlacemarks.push(this.createBoxPolygon(ge, -180, @east))
    else
      @boxPlacemarks.push(this.createBoxPolygon(ge, @west, @east))

    _.each(@boxPlacemarks, (p)->
      ge.getFeatures().appendChild(p))

  undisplay: (ge) ->
    _.each(@boxPlacemarks, (p)->
      ge.getFeatures().removeChild(p))
    @boxPlacemarks = []

  crossesAntimeridan: ->
    @west > @east

  zoomablePoints: ()->
    [new Point(@west, @north), new Point(@east, @south)]

  # A helper that creates a polygon placemark with the given bounds.
  createBoxPolygon: (ge, west, east, north=@north, south=@south)->
    polygonPlacemark = ge.createPlacemark("")
    polygon = ge.createPolygon("")
    polygon.setAltitudeMode(ge.ALTITUDE_CLAMP_TO_GROUND)
    polygonPlacemark.setGeometry(polygon)

    polygonPlacemark.setStyleSelector(ge.createStyle(''))
    polyStyle = polygonPlacemark.getStyleSelector().getPolyStyle()
    polyStyle.getColor().set(@color)

    ring = ge.createLinearRing("")
    # add points in nw,ne,se,sw order
    c = ring.getCoordinates()
    c.pushLatLngAlt(north, west, 0)
    c.pushLatLngAlt(north, east, 0)
    c.pushLatLngAlt(south, east, 0)
    c.pushLatLngAlt(south, west, 0)
    polygon.setOuterBoundary(ring)

    polygonPlacemark


