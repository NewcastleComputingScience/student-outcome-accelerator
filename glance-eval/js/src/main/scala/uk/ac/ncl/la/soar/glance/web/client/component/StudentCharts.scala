/** soar
  *
  * Copyright (c) 2017 Hugo Firth
  * Email: <me@hugofirth.com/>
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at:
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package uk.ac.ncl.la.soar.glance.web.client.component

import uk.ac.ncl.la.soar.ModuleCode
import uk.ac.ncl.la.soar.data.StudentRecords
import diode.data._
import diode.react.ReactPot._
import diode.react._
import cats._
import cats.data.NonEmptyVector
import cats.implicits._
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import uk.ac.ncl.la.soar.ModuleCode
import uk.ac.ncl.la.soar.data.StudentRecords
import uk.ac.ncl.la.soar.glance.web.client.data.CohortAttainmentSummary
import uk.ac.ncl.la.soar.glance.web.client.style.Icon

import scala.collection.immutable.SortedMap
import scala.collection.mutable.ListBuffer
import scala.scalajs.js

/**
  * Simple component for rendering charts describing some stat for an individual student
  */
object StudentCharts {

  type Filter = (ModuleCode, Double) => Boolean

  // Default options for filter component prototype, will be read in as part of props eventually.
  private val options: NonEmptyVector[Select.Choice[Filter]] =
    NonEmptyVector(
      Select.Choice((_, _) => true, "None"),
      Vector(
        Select.Choice((mc, _) => mc <= "CSC3723", "Stage 3"),
        Select.Choice((mc, _) => mc <= "CSC2026", "Stage 2"),
        Select.Choice((mc, _) => mc <= "CSC1026", "Stage 1")
      )
    )

  case class Props(student: Option[StudentRecords[SortedMap, ModuleCode, Double]],
                   cohort: CohortAttainmentSummary,
                   filterChoices: NonEmptyVector[Select.Choice[Filter]] = options)

  case class State(selectedFilters: Set[Select.Choice[Filter]], cohortComparison: Boolean)

  class Backend(bs: BackendScope[Props, State]) {

    def mounted(p: Props) = Callback { println("Bars did mount") }

    def render(p: Props, s: State): VdomElement = {
      <.div(
        ^.id := "detailed",
        p.student.fold[TagMod](<.p(^.className := "chart-placedholder", "Click on a student")) { student =>
          List(
            drawBars(filtered(student, s.selectedFilters),
              filtered(p.cohort.toRecord, s.selectedFilters),
              s.cohortComparison),
            drawLines(filtered(student, s.selectedFilters),
              filtered(p.cohort.toRecord, s.selectedFilters),
              s.cohortComparison),
            <.div(
              ^.className := "chart-controls",
              <.div(
                ^.className := "row",
                drawFilters(p.filterChoices, s.selectedFilters),
                drawCheckbox(s.cohortComparison)
              )
            )
          ).toTagMod

        }
      )
    }

    /** Filter Student Records */
    private def filtered(records: StudentRecords[SortedMap, ModuleCode, Double],
                         filters: Set[Select.Choice[Filter]]) = {

      //TODO: Composing functions is interesting, but should I apply (lazily) and then reduce rather than applying *as* I
      // reduce?
      val combinedFilter = filters.iterator.map(_.value).reduceOption { (fAcc, f) =>
        (mc: ModuleCode, s: Double) => fAcc(mc, s) && f(mc, s)
      }
      combinedFilter.fold(records.record)(choice => records.record.filter { case (mc, s) => choice(mc, s) })
    }

    /** Construct line chart representation of student average over time, as a proof of concept */
    private def drawLines(studentScores: SortedMap[ModuleCode, Double],
                          cohortSummary: SortedMap[ModuleCode, Double],
                          drawCohortSummary: Boolean) = {
      //Calculate trend line
      def trend(data: Iterable[(ModuleCode, Double)]): List[Double] = {
        //Very mutable, but I'm trying to get back into the habit of method local mutability.
        var total = 0.0
        var counter = 0.0
        val aBldr = ListBuffer.empty[Double]
        for((_, r) <- data) {
          total += r
          counter += 1
          aBldr += (total / counter)
        }
        aBldr.result()
      }

      val averages = trend(studentScores)

      val studentDataset = List(ChartDataset(averages, "Average score", "rgba(111, 203, 118, 0.1)", "#47CB50"))

      val datasets = if(drawCohortSummary) {
        val cohortAverages = trend(cohortSummary)
        ChartDataset(cohortAverages, "Cohort average score", "rgba(128, 128, 255, 0.1)") :: studentDataset
      } else studentDataset

      //List of blank strings required rather than just having no labels as otherwise Chart.js only renders first point
      val labels = averages.map(_ => "")
      val chartData = ChartData(labels, datasets)
      val p = Chart.Props("Average Score Over Time", Chart.LineChart, chartData,
        ChartOptions(displayLegend = true))

      <.div(^.className := "chart-container", Chart.component(p))
    }

    /** Construct detailed representation of student scores, including viz */
    private def drawBars(data: SortedMap[ModuleCode, Double],
                         cohortSummary: SortedMap[ModuleCode, Double],
                         drawCohortSummary: Boolean)  = {

      //TODO: Make colours package private constants

      //Generate custom legend for student bars
      val generateLegends: JSChart => Seq[ChartLegendItem] = { chart =>

        val triColorData = Array(
          ChartLegendItem("Distinction", "#6FCB76", "#47CB50"),
          ChartLegendItem("Pass", "#CBCB72", "#CBC754"),
          ChartLegendItem("Fail", "#CB4243", "#CB3131")
        )

        if(chart.data.datasets.length > 1)
          ChartLegendItem("Cohort Module Scores", "#8080FF", "#404080") +: triColorData
        else
          triColorData
      }

      //Create a props object for the chart component based on a StudentRecords object
      //Get the module labels and scores
      val mB = ListBuffer.empty[ModuleCode]
      val sB = ListBuffer.empty[Double]
      for ((module, score) <- data) {
        mB += module
        sB += score
      }
      val modules = mB.toList
      val scores = sB.toList

      val (fillColours, borderColours) = colourBars(scores)

      val studentDataset = List(ChartDataset(scores, "Module Scores", fillColours, borderColours))

      val datasets = if(drawCohortSummary) {
        ChartDataset(cohortSummary.values.toSeq, "Cohort Module Scores") :: studentDataset
      } else studentDataset

      val chartData = ChartData(modules, datasets)
      val p = Chart.Props("Student Module Scores", Chart.BarChart, chartData,
        ChartOptions(displayLegend = true, generateLegend = generateLegends.some))

      <.div(^.className := "chart-container", Chart.component(p))
    }

    /** Calculate List of colours for student bars */
    private def colourBars(scores: List[Double]): (List[String], List[String]) = {
      //Constants representing colours (fail, pass, good)
      val borderColours = ("#CB3131", "#CBC754", "#47CB50")
      val fillColours = ("#CB4243", "#CBCB72", "#6FCB76")

      def colourPicker(score: Int, colours: (String, String, String)) = {
        if(score < 40) colours._1
        else if (score < 60) colours._2
        else colours._3
      }

      val fills = scores.iterator.map(s => colourPicker(s.toInt, fillColours))
      val borders = scores.iterator.map(s => colourPicker(s.toInt, borderColours))
      (fills.toList, borders.toList)
    }

    /** Draw filter form group */
    //TODO: Abstract Multiselect features into its own component at some point
    private def drawFilters(choices: NonEmptyVector[Select.Choice[Filter]], selected: Set[Select.Choice[Filter]]) = {
      <.div(
        ^.className := "col-lg-6",
        <.div(
          ^.className := "input-group",
          <.div(
            ^.className := "input-group-addon",
            Icon.filter(Icon.Small), "Filters:  "),
          <.div(
            ^.className := "bootstrap-tagsinput",
            if(selected.isEmpty) {
              <.span(^.id := "filters-placeholder", "Active Filters ...")
            } else {
              selected.toTagMod { s =>
                <.span(
                  ^.className := "tag label label-info",
                  s.label,
                  <.span(
                    VdomAttr("data-role") := "remove",
                    ^.onClick --> filterRemove(s)
                  )
                )
              }
            }
          ),
          Select.component(Select.Props(selected.headOption.getOrElse(choices.head),
            choices.toVector, filterSelect, "Choose  ".some))
        )
      )
    }

    /** Handle filter select */
    private def filterSelect(selected: Select.Choice[Filter]) =
      bs.modState(s => s.copy(selectedFilters = s.selectedFilters + selected))

    /** Handle filter remove */
    private def filterRemove(removed: Select.Choice[Filter]) =
      bs.modState(s => s.copy(selectedFilters = s.selectedFilters - removed))

    /** Handle cohort comparison toggle */
    private def cohortToggle(e: ReactEventFromInput) = bs.modState(s => s.copy(cohortComparison = !s.cohortComparison))

    /** Draw cohort comparison checkbox */
    private def drawCheckbox(cohortSummary: Boolean): VdomElement = {
      <.div(
        ^.className := "col-lg-3 col-lg-offset-3",
        <.div(
          ^.className := "input-group",
          ^.id := "cohort-summary",
          <.label(
            (if(cohortSummary)
              ^.className := "btn btn-primary"
            else
              ^.className := "btn btn-default"),
            <.input(
              ^.id := "cohort-summary-toggle",
              ^.`type` := "checkbox",
              ^.selected := cohortSummary,
              ^.onChange ==> cohortToggle
            ),
            "  Cohort Summary"
          )
        )

      )
    }

  }

  val component = ScalaComponent.builder[Props]("StudentBars")
    .initialStateFromProps(p => State(Set.empty[Select.Choice[Filter]], false))
    .renderBackend[Backend]
    .componentDidMount(scope => scope.backend.mounted(scope.props))
    .build

}